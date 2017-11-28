package com.sos.jobscheduler.agent.scheduler

import com.sos.jobscheduler.base.circeutils.typed.Subtype
import com.sos.jobscheduler.base.circeutils.typed.TypedJsonCodec
import com.sos.jobscheduler.common.auth.UserId
import io.circe.generic.JsonCodec

/**
  * @author Joacim Zschimmer
  */
private[scheduler] sealed trait AgentSnapshot

private[scheduler] object AgentSnapshot {
  @JsonCodec
  final case class Master(userId: UserId) extends AgentSnapshot

  val JsonCodec = TypedJsonCodec[Any](
    Subtype[Master])
}
