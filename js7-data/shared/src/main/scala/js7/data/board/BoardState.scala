package js7.data.board

import js7.base.problem.{Checked, Problem}
import js7.data.order.OrderId
import monix.reactive.Observable

final case class BoardState(
  board: Board,
  idToNotice: Map[NoticeId, NoticeIdState] = Map.empty)
{
  def path = board.path

  def toSnapshotObservable: Observable[Any] = {
    board +: Observable.fromIterable(notices.map(Notice.Snapshot(board.path, _)))
    // AwaitingNotice are recovered from Order[Order.WaitingForNotice]
  }

  def addNotice(notice: Notice): BoardState =
    copy(
      idToNotice = idToNotice + (notice.id -> notice))

  def addWaitingOrder(orderId: OrderId, noticeId: NoticeId): Checked[BoardState] =
    idToNotice.get(noticeId) match {
      case None =>
        Right(copy(
          idToNotice = idToNotice +
            (noticeId -> AwaitingNotice(noticeId, orderId :: Nil))))

      case Some(awaitingNotice: AwaitingNotice) =>
        Right(copy(
          idToNotice = idToNotice +
            (noticeId -> awaitingNotice.copy(
              awaitingOrderIds = awaitingNotice.awaitingOrderIds.view.appended(orderId).toVector))))

      case Some(_: Notice) =>
        Left(Problem("BoardState.addWaitingOrder despite notice has been posted"))
    }

  def waitingOrders(noticeId: NoticeId): Seq[OrderId] =
    idToNotice.get(noticeId) match {
      case Some(AwaitingNotice(_, orderIds)) => orderIds
      case _ => Nil
    }

  def notices: Iterable[Notice] =
    idToNotice.values.view.collect { case o: Notice => o }

  def deleteNotice(noticeId: NoticeId): BoardState =
    copy(idToNotice = idToNotice - noticeId)
}
