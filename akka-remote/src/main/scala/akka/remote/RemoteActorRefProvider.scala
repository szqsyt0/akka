/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.dispatch._
import akka.util.duration._
import akka.util.Timeout
import akka.config.ConfigurationException
import akka.event.{ DeathWatch, Logging }
import akka.serialization.Compression.LZF
import com.google.protobuf.ByteString
import akka.event.EventStream
import akka.dispatch.Promise
import akka.config.ConfigurationException
import java.util.concurrent.{ TimeoutException }
import com.typesafe.config.Config
import akka.util.ReflectiveAccess
import akka.serialization.Serialization
import akka.serialization.SerializationExtension

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 */
class RemoteActorRefProvider(
  val systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  _deadLetters: InternalActorRef) extends ActorRefProvider {

  val remoteSettings = new RemoteSettings(settings.config, systemName)

  def rootGuardian = local.rootGuardian
  def guardian = local.guardian
  def systemGuardian = local.systemGuardian
  def terminationFuture = local.terminationFuture
  def dispatcher = local.dispatcher

  val deployer = new RemoteDeployer(settings)

  val transport: RemoteTransport = {
    val fqn = remoteSettings.RemoteTransport
    // TODO check if this classloader is the right one
    ReflectiveAccess.createInstance[RemoteTransport](
      fqn,
      Seq(classOf[RemoteSettings] -> remoteSettings),
      getClass.getClassLoader) match {
        case Left(problem) ⇒ throw new RemoteTransportException("Could not load remote transport layer " + fqn, problem)
        case Right(remote) ⇒ remote
      }
  }

  val log = Logging(eventStream, "RemoteActorRefProvider(" + transport.address + ")")

  val rootPath: ActorPath = RootActorPath(transport.address)

  private val local = new LocalActorRefProvider(systemName, settings, eventStream, scheduler, _deadLetters, rootPath, deployer)

  val failureDetector = new AccrualFailureDetector(remoteSettings.FailureDetectorThreshold, remoteSettings.FailureDetectorMaxSampleSize)

  @volatile
  private var _serialization: Serialization = _
  def serialization = _serialization

  @volatile
  private var _remoteDaemon: InternalActorRef = _
  def remoteDaemon = _remoteDaemon

  @volatile
  private var _networkEventStream: NetworkEventStream = _
  def networkEventStream = _networkEventStream

  val deathWatch = new RemoteDeathWatch(local.deathWatch, this)

  def init(system: ActorSystemImpl) {
    local.init(system)

    _remoteDaemon = new RemoteSystemDaemon(system, transport.address, rootPath / "remote", rootGuardian, log)
    _serialization = SerializationExtension(system)

    transport.start(system, this)

    val remoteClientLifeCycleHandler = system.systemActorOf(Props(new Actor {
      def receive = {
        case RemoteClientError(cause, remote, address) ⇒ remote.shutdownClientConnection(address)
        case RemoteClientDisconnected(remote, address) ⇒ remote.shutdownClientConnection(address)
        case _                                         ⇒ //ignore other
      }
    }), "RemoteClientLifeCycleListener")

    _networkEventStream = new NetworkEventStream(system)

    system.eventStream.subscribe(networkEventStream.sender, classOf[RemoteLifeCycleEvent])
    system.eventStream.subscribe(remoteClientLifeCycleHandler, classOf[RemoteLifeCycleEvent])

    local.registerExtraNames(Map(("remote", remoteDaemon)))
    terminationFuture.onComplete(_ ⇒ transport.shutdown())
  }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath, systemService: Boolean, deploy: Option[Deploy]): InternalActorRef = {
    if (systemService) local.actorOf(system, props, supervisor, path, systemService, deploy)
    else {

      /*
       * This needs to deal with “mangled” paths, which are created by remote
       * deployment, also in this method. The scheme is the following:
       *
       * Whenever a remote deployment is found, create a path on that remote
       * address below “remote”, including the current system’s identification
       * as “sys@host:port” (typically; it will use whatever the remote
       * transport uses). This means that on a path up an actor tree each node
       * change introduces one layer or “remote/sys@host:port/” within the URI.
       *
       * Example:
       *
       * akka://sys@home:1234/remote/sys@remote:6667/remote/sys@other:3333/user/a/b/c
       *
       * means that the logical parent originates from “sys@other:3333” with
       * one child (may be “a” or “b”) being deployed on “sys@remote:6667” and
       * finally either “b” or “c” being created on “sys@home:1234”, where
       * this whole thing actually resides. Thus, the logical path is
       * “/user/a/b/c” and the physical path contains all remote placement
       * information.
       *
       * Deployments are always looked up using the logical path, which is the
       * purpose of the lookupRemotes internal method.
       */

      @scala.annotation.tailrec
      def lookupRemotes(p: Iterable[String]): Option[Deploy] = {
        p.headOption match {
          case None           ⇒ None
          case Some("remote") ⇒ lookupRemotes(p.drop(2))
          case Some("user")   ⇒ deployer.lookup(p.drop(1).mkString("/", "/", ""))
          case Some(_)        ⇒ None
        }
      }

      val elems = path.elements
      val deployment = deploy orElse (elems.head match {
        case "user"   ⇒ deployer.lookup(elems.drop(1).mkString("/", "/", ""))
        case "remote" ⇒ lookupRemotes(elems)
        case _        ⇒ None
      })

      deployment match {
        case Some(Deploy(_, _, _, RemoteScope(addr))) ⇒
          if (addr == rootPath.address) local.actorOf(system, props, supervisor, path, false, deployment)
          else {
            val rpath = RootActorPath(addr) / "remote" / rootPath.address.hostPort / path.elements
            useActorOnNode(rpath, props.creator, supervisor)
            new RemoteActorRef(this, transport, rpath, supervisor, None)
          }

        case _ ⇒ local.actorOf(system, props, supervisor, path, systemService, deployment)
      }
    }
  }

  def actorFor(path: ActorPath): InternalActorRef = path.root match {
    case `rootPath` ⇒ actorFor(rootGuardian, path.elements)
    case _          ⇒ new RemoteActorRef(this, transport, path, Nobody, None)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case ActorPathExtractor(address, elems) ⇒
      if (address == rootPath.address) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, transport, new RootActorPath(address) / elems, Nobody, None)
    case _ ⇒ local.actorFor(ref, path)
  }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef = local.actorFor(ref, path)

  def ask(within: Timeout): Option[AskActorRef] = local.ask(within)

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(path: ActorPath, actorFactory: () ⇒ Actor, supervisor: ActorRef) {
    log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, path)

    // we don’t wait for the ACK, because the remote end will process this command before any other message to the new actor
    actorFor(RootActorPath(path.address) / "remote") ! DaemonMsgCreate(actorFactory, path.toString, supervisor)
  }
}

trait RemoteRef extends ActorRefScope {
  final def isLocal = false
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 */
private[akka] class RemoteActorRef private[akka] (
  provider: RemoteActorRefProvider,
  remote: RemoteTransport,
  val path: ActorPath,
  val getParent: InternalActorRef,
  loader: Option[ClassLoader])
  extends InternalActorRef with RemoteRef {

  def getChild(name: Iterator[String]): InternalActorRef = {
    val s = name.toStream
    s.headOption match {
      case None       ⇒ this
      case Some("..") ⇒ getParent getChild name
      case _          ⇒ new RemoteActorRef(provider, remote, path / s, Nobody, loader)
    }
  }

  @volatile
  private var running: Boolean = true

  def isTerminated: Boolean = !running

  def sendSystemMessage(message: SystemMessage): Unit = remote.send(message, None, this, loader)

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = remote.send(message, Option(sender), this, loader)

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = {
    provider.ask(timeout) match {
      case Some(a) ⇒
        this.!(message)(a)
        a.result
      case None ⇒
        this.!(message)(null)
        Promise[Any]()(provider.dispatcher)
    }
  }

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(): Unit = sendSystemMessage(Resume())

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path.toString)
}

class RemoteDeathWatch(val local: LocalDeathWatch, val provider: RemoteActorRefProvider) extends DeathWatch {

  def subscribe(watcher: ActorRef, watched: ActorRef): Boolean = watched match {
    case r: RemoteRef ⇒
      val ret = local.subscribe(watcher, watched)
      provider.actorFor(r.path.root / "remote") ! DaemonMsgWatch(watcher, watched)
      ret
    case l: LocalRef ⇒
      local.subscribe(watcher, watched)
    case _ ⇒
      provider.log.error("unknown ActorRef type {} as DeathWatch target", watched.getClass)
      false
  }

  def unsubscribe(watcher: ActorRef, watched: ActorRef): Boolean = local.unsubscribe(watcher, watched)

  def unsubscribe(watcher: ActorRef): Unit = local.unsubscribe(watcher)

  def publish(event: Terminated): Unit = local.publish(event)

}
