/**
  * Copyright 2015 Otto (GmbH & Co KG)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package org.schedoscope.scheduler.actors

import java.security.PrivilegedAction

import akka.actor.{Actor, ActorRef, Props, actorRef2Scala}
import akka.event.{Logging, LoggingReceive}
import org.apache.commons.lang.exception.ExceptionUtils
import org.apache.hadoop.fs._
import org.schedoscope.conf.{DriverSettings, SchedoscopeSettings}
import org.schedoscope.dsl.View
import org.schedoscope.dsl.transformations.{Transformation, _}
import org.schedoscope.scheduler.driver.FilesystemDriver.defaultFileSystem
import org.schedoscope.scheduler.driver._
import org.schedoscope.scheduler.messages._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

/**
  * A driver actor manages the executions of transformations using hive, oozie etc. The actual
  * execution is done using a driver trait implementation. The driver actor code itself is transformation
  * type agnostic. Driver actors receive the transformation tasks they execute from the transformation manager actor
  * via intermediate driver router actor
  *
  */
class DriverActor[T <: Transformation](transformationManagerActor: ActorRef,
                                       ds: DriverSettings,
                                       driverConstructor: (DriverSettings) => Driver[T],
                                       pingDuration: FiniteDuration,
                                       settings: SchedoscopeSettings,
                                       hdfs: FileSystem) extends Actor {

  import context._

  val log = Logging(system, this)

  var driver: Driver[T] = _

  var runningCommand: Option[DriverCommand] = None

  val driverRouter = context.parent


  /**
    * Log state upon start.
    */
  override def preStart() {
    try {
      driver = driverConstructor(ds)
    } catch {
      case t: Throwable => throw RetryableDriverException("Driver actor could not initialize driver because driver constructor throws exception (HINT: if Driver Actor start failure behaviour persists, validate the respective transformation driver config in conf file). Restarting driver actor...", t)
    }
    logStateInfo("booted", "DRIVER ACTOR: booted")
  }

  /**
    * If the driver actor is being restarted by the driver router actor, the currently running action is re-distributed to driver router for load balancing to another worker, and so it does not get lost.
    */
  override def preRestart(reason: Throwable, message: Option[Any]) {
    if (runningCommand.isDefined)
      driverRouter ! runningCommand.get
  }

  /**
    * Provide continuous ticking in default state
    */
  def tick() {
    system.scheduler.scheduleOnce(pingDuration, self, "tick")
  }

  /**
    * Message handler for the default state.
    * Transitions only to state activeReceive
    */
  def receive = LoggingReceive {
    case "tick" => toActiveReceive()

    case c: DriverCommand => driverRouter ! c

    case "reboot" => throw new RetryableDriverException()
  }

  /**
    * Message handler for the actively processing
    * DriverCommand messages.
    * Transitions only to state active running
    */
  def activeReceive = LoggingReceive {
    case t: DriverCommand => toRunning(t)

    case "reboot" => throw new RetryableDriverException()
  }

  /**
    * Message handler for the running state
    *
    * @param runHandle      reference to the running driver
    * @param originalSender reference to the viewActor that requested the transformation (for sending back the result)
    */
  def running(runHandle: DriverRunHandle[T], originalSender: ActorRef, transformingView: Option[View]): Receive = LoggingReceive {
    case KillCommand() => {
      driver.killRun(runHandle)
      toActiveReceive()
    }
    // If getting a command while being busy, reschedule it by sending it to the driver router for load balancing
    case c: DriverCommand => driverRouter ! c

    // check all 10 seconds the state of the current running driver
    case "tick" => try {
      driver.getDriverRunState(runHandle) match {
        case _: DriverRunOngoing[T] => tick()

        case success: DriverRunSucceeded[T] => {

          log.info(s"DRIVER ACTOR: Driver run for handle=${runHandle} succeeded.")

          try {
            driver.driverRunCompleted(runHandle)
          } catch {
            case d: RetryableDriverException => throw d

            case t: Throwable => {
              log.error(s"DRIVER ACTOR: Driver run for handle=${runHandle} failed because completion handler threw exception ${t}, trace ${ExceptionUtils.getStackTrace(t)}")

              sendTransformationResult(transformingView,
                originalSender,
                TransformationFailure(runHandle, DriverRunFailed[T](driver, "Completition handler failed", t)))
              toActiveReceive()
            }
          }

          //check if transformation produced some data
          val viewHasData = transformingView match {
            case Some(view) =>
              if (runHandle.transformation.isInstanceOf[NoOp]) {
                successFlagExists(view)
              } else {
                !folderEmpty(view)
              }
            case None =>
              false
          }

          sendTransformationResult(transformingView,
            originalSender,
            TransformationSuccess(runHandle, success, viewHasData))
          toActiveReceive()
        }

        case failure: DriverRunFailed[T] => {
          log.error(s"DRIVER ACTOR: Driver run for handle=${runHandle} failed. ${failure.reason}, cause ${failure.cause}, trace ${if (failure.cause != null) ExceptionUtils.getStackTrace(failure.cause) else "no trace available"}")

          try {
            driver.driverRunCompleted(runHandle)
          } catch {
            case d: RetryableDriverException => throw d

            case t: Throwable => {
            }
          }

          sendTransformationResult(transformingView,
            originalSender,
            TransformationFailure(runHandle, failure))
          toActiveReceive()
        }
      }
    } catch {
      case exception: RetryableDriverException => {
        log.error(s"DRIVER ACTOR: Driver exception caught by driver actor in running state, rethrowing: ${exception.message}, cause ${exception.cause}, trace ${ExceptionUtils.getStackTrace(exception)}")
        throw exception
      }

      case t: Throwable => {
        log.error(s"DRIVER ACTOR: Unexpected exception caught by driver actor in running state, rethrowing: ${t.getMessage()}, cause ${t.getCause()}, trace ${ExceptionUtils.getStackTrace(t)}")
        throw t
      }
    }

    case "reboot" => throw new RetryableDriverException(s"Received reboot command from ${sender.path.toStringWithoutAddress}")

  }

  def sendTransformationResult(transformingView: Option[View], actorRef: ActorRef, msg: AnyRef): Unit = {
    val message = transformingView match {
      case Some(v) => CommandForView(None, v, msg)
      case None => msg
    }
    actorRef ! message
  }

  /**
    * State transition to default state.
    */
  def toActiveReceive() {
    runningCommand = None

    logStateInfo("idle", "DRIVER ACTOR: becoming idle")

    become(activeReceive)
  }

  /**
    * State transition to running state.
    *
    * Includes special handling of "Deploy" commands, those are executed directly, no state transition despite name of function
    * Otherwise run the transformation using the driver instance and switch to running state
    *
    * @param commandToRun
    */
  def toRunning(commandToRun: DriverCommand) {
    runningCommand = Some(commandToRun)

    try {
      commandToRun.command match {
        case DeployCommand() =>
          logStateInfo("deploy", s"DRIVER ACTOR: Running Deploy command")

          driver.deployAll(ds)
          commandToRun.sender ! DeployCommandSuccess()

          logStateInfo("idle", "DRIVER ACTOR: becoming idle")
          runningCommand = None
        case TransformView(t, view) =>
          val transformation: T = t.asInstanceOf[T]

          val runHandle = driver.run(transformation)
          driver.driverRunStarted(runHandle)

          logStateInfo("running", s"DRIVER ACTOR: Running transformation ${transformation}, configuration=${transformation.configuration}, runHandle=${runHandle}", runHandle, driver.getDriverRunState(runHandle))
          tick()
          become(running(runHandle, commandToRun.sender, Some(view)))
        case t: Transformation =>
          val transformation: T = t.asInstanceOf[T]

          val runHandle = driver.run(transformation)
          driver.driverRunStarted(runHandle)

          logStateInfo("running", s"DRIVER ACTOR: Running transformation ${transformation}, configuration=${transformation.configuration}, runHandle=${runHandle}", runHandle, driver.getDriverRunState(runHandle))
          tick()
          become(running(runHandle, commandToRun.sender, None))
      }
    } catch {
      case retryableException: RetryableDriverException => {
        log.error(s"DRIVER ACTOR: Driver exception caught by driver actor in receive state, rethrowing: ${retryableException.message}, cause ${retryableException.cause}")

        throw retryableException
      }

      case t: Throwable => {
        log.error(s"DRIVER ACTOR: Unexpected exception caught by driver actor in receive state, rethrowing: ${t.getMessage()}, cause ${t.getCause()}")

        throw t
      }
    }
  }

  def logStateInfo(state: String, message: String, runHandle: DriverRunHandle[T] = null, runState: DriverRunState[T] = null) {
    transformationManagerActor ! TransformationStatusResponse(state, self, driver, runHandle, runState)
    log.info(message)
  }

  def successFlagExists(view: View) = settings
    .userGroupInformation.doAs(
    new PrivilegedAction[Boolean]() {
      def run() = {
        hdfs.exists(new Path(view.fullPath + "/_SUCCESS"))
      }
    })

  def folderEmpty(view: View) = settings
    .userGroupInformation.doAs(
    new PrivilegedAction[Array[FileStatus]]() {
      def run() = {
        hdfs.listStatus(new Path(view.fullPath), new PathFilter() {
          def accept(p: Path): Boolean = !p.getName.startsWith("_")
        })
      }
    })
    .foldLeft(0l) {
      (size, status) => size + status.getLen
    } <= 0
}

/**
  * Factory methods for driver actors.
  */
object DriverActor {
  def props(settings: SchedoscopeSettings, transformationName: String, transformationManager: ActorRef, hdfs: FileSystem): Props =
    Props(
      classOf[DriverActor[_]],
      transformationManager,
      settings.getDriverSettings(transformationName),
      (ds: DriverSettings) => Driver.driverFor(ds),
      if (transformationName == "filesystem")
        100 milliseconds
      else if (transformationName == "noop")
        10 milliseconds
      else
        5 seconds,
      settings,
      hdfs: FileSystem).withDispatcher("akka.actor.driver-dispatcher")

  def props(settings: SchedoscopeSettings, transformationName: String, transformationManager: ActorRef): Props =
    props(settings,
      transformationName,
      transformationManager,
      defaultFileSystem(settings.hadoopConf))


}
