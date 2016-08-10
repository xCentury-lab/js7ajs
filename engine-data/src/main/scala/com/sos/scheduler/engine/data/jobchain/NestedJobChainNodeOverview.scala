package com.sos.scheduler.engine.data.jobchain

import spray.json.DefaultJsonProtocol._

final case class NestedJobChainNodeOverview(
  nodeKey: NodeKey,
  nextNodeId: NodeId,
  errorNodeId: NodeId,
  nestedJobChainPath: JobChainPath)
extends NodeOverview

object NestedJobChainNodeOverview {
  implicit val MyJsonFormat = jsonFormat4(apply)
}
