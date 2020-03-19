package com.sos.jobscheduler.master.cluster

import com.sos.jobscheduler.base.eventbus.EventBus
import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.problem.Checked
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.core.cluster.ClusterWatch.ClusterWatchHeartbeatFromInactiveNodeProblem
import com.sos.jobscheduler.core.cluster.ClusterWatchApi
import com.sos.jobscheduler.data.cluster.{ClusterEvent, ClusterState}
import com.sos.jobscheduler.data.common.Uri
import com.sos.jobscheduler.master.cluster.ClusterCommon._
import com.sos.jobscheduler.master.cluster.PassiveClusterNode.{ClusterWatchAgreesToActivation, ClusterWatchDisagreeToActivation}
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, GatheringByteChannel, ScatteringByteChannel}
import java.nio.file.StandardOpenOption.{CREATE, READ, TRUNCATE_EXISTING, WRITE}
import java.nio.file.{Path, Paths}
import monix.eval.Task

private[cluster] final class ClusterCommon(
  ownUri: Uri,
  activationInhibitor: ActivationInhibitor,
  val clusterWatch: ClusterWatchApi,
  testEventBus: EventBus)
{
  private[cluster] def ifClusterWatchAllowsActivation[A](clusterState: ClusterState, event: ClusterEvent, body: Task[Checked[Boolean]])
  : Task[Checked[Boolean]] =
    activationInhibitor.tryToActivate(
      ifInhibited = Task.pure(Right(false)),  // Ignore heartbeat loss
      activate =
        clusterState.applyEvent(event) match {
          case Left(problem) => Task.pure(Left(problem))
          case Right(updatedClusterState) =>
            clusterWatch.applyEvents(from = ownUri, event :: Nil, updatedClusterState).flatMap {
              case Left(problem) =>
                if (problem.codeOption contains ClusterWatchHeartbeatFromInactiveNodeProblem.code) {
                  logger.info(s"ClusterWatch did not agree to failover: $problem")
                  testEventBus.publish(ClusterWatchDisagreeToActivation)
                  Task.pure(Right(false))  // Ignore heartbeat loss
                } else
                  Task.pure(Left(problem))

              case Right(Completed) =>
                logger.info(s"ClusterWatch agreed to failover")
                testEventBus.publish(ClusterWatchAgreesToActivation)
                body
            }
        }
    )
}

private[cluster] object ClusterCommon
{
  private val logger = Logger(getClass)

  private[cluster] def truncateFile(file: Path, position: Long): Unit = {
    autoClosing(FileChannel.open(file, READ, WRITE)) { f =>
      autoClosing(FileChannel.open(Paths.get(file.toString + "~TRUNCATED-AFTER-FAILOVER"), WRITE, CREATE, TRUNCATE_EXISTING)) { out =>
        val buffer = ByteBuffer.allocate(4096)
        f.position(position - 1)
        f.read(buffer)
        buffer.flip()
        if (!buffer.hasRemaining || buffer.get() != '\n')
          sys.error(s"Invalid failed-over position=$position in '${file.getFileName} journal file")
        copy(f, out, buffer)
        f.truncate(position)
      }
    }
  }

  private def copy(in: ScatteringByteChannel, out: GatheringByteChannel, buffer: ByteBuffer): Unit = {
    var eof = false
    while(!eof) {
      if (buffer.hasRemaining) out.write(buffer)
      buffer.clear()
      eof = in.read(buffer) <= 0
      buffer.flip()
    }
  }
}
