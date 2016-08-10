package com.sos.scheduler.engine.data.order

import com.sos.scheduler.engine.data.jobchain.NodeId

final case class OrderFinished(orderKey: OrderKey, nodeId: NodeId)
extends OrderEvent
