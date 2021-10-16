package js7.data.controller

import cats.syntax.traverse._
import js7.base.problem.Problems.DuplicateKey
import js7.base.problem.{Checked, Problem}
import js7.base.utils.ScalaUtils.syntax.{RichBoolean, RichEither, RichPartialFunction}
import js7.data.controller.VerifiedUpdateItemsExecutor._
import js7.data.crypt.SignedItemVerifier
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.{KeyedEvent, NoKeyEvent}
import js7.data.item.BasicItemEvent.{ItemDeleted, ItemDeletionMarked}
import js7.data.item.SignedItemEvent.{SignedItemAdded, SignedItemChanged}
import js7.data.item.UnsignedSimpleItemEvent.{UnsignedSimpleItemAdded, UnsignedSimpleItemAddedOrChanged, UnsignedSimpleItemChanged}
import js7.data.item.VersionedEvent.VersionedItemRemoved
import js7.data.item.{BasicItemEvent, InventoryItem, InventoryItemEvent, InventoryItemPath, ItemRevision, SignableSimpleItem, SimpleItemPath, UnsignedSimpleItem, VersionedEvent, VersionedItemPath}
import scala.collection.View

final case class VerifiedUpdateItemsExecutor(
  verifiedUpdateItems: VerifiedUpdateItems,
  controllerState: ControllerState,
  checkItem: InventoryItem => Checked[Unit])
{
  def executeVerifiedUpdateItems: Checked[Seq[KeyedEvent[NoKeyEvent]]] =
    ( for {
      versionedEvents <- versionedEvents
      simpleItemEvents <- simpleItemEvents
      updatedState <- controllerState.applyEvents(
          (versionedEvents.view ++ simpleItemEvents).map(NoKey <-: _))
      updatedState <- updatedState.applyEvents(
          versionedEvents.view
            .collect { case e: VersionedItemRemoved => e.path }
            .flatMap(deleteRemovedVersionedItem(updatedState, _)))
      _ <- checkVerifiedUpdateConsistency(verifiedUpdateItems, updatedState)
      } yield (simpleItemEvents ++ versionedEvents).map(NoKey <-: _).toVector
    ) .left.map {
      case prblm @ Problem.Combined(Seq(_, duplicateKey: DuplicateKey)) =>
        scribe.debug(prblm.toString)
        duplicateKey
      case o => o
    }

  private def versionedEvents: Checked[Seq[VersionedEvent]] =
    verifiedUpdateItems.maybeVersioned match {
      case None => Right(Nil)
      case Some(versioned) =>
        controllerState.repo.itemsToEvents(
          versioned.versionId,
          versioned.verifiedItems.map(_.signedItem),
          versioned.remove)
    }

  private def simpleItemEvents: Checked[View[InventoryItemEvent]] = {
    import verifiedUpdateItems.simple
    simple.verifiedSimpleItems
      .traverse(verifiedSimpleItemToEvent)
      .flatMap(signedEvents =>
        simple.unsignedSimpleItems
          .traverse(unsignedSimpleItemToEvent)
          .map(unsignedEvents =>
            simple.delete.view
              .flatMap(simpleItemDeletionEvents)
              .view ++ signedEvents ++ unsignedEvents))
  }

  private def verifiedSimpleItemToEvent(verified: SignedItemVerifier.Verified[SignableSimpleItem])
  : Checked[InventoryItemEvent] = {
    val item = verified.item
    if (item.itemRevision.isDefined)
      Left(Problem.pure("ItemRevision is not accepted here"))
    else
      Right(
        controllerState.pathToSimpleItem.get(item.key) match {
          case None =>
            SignedItemAdded(verified.signedItem.copy(value =
              item.withRevision(Some(ItemRevision.Initial))))
          case Some(existing) =>
            SignedItemChanged(verified.signedItem.copy(
              value = verified.signedItem.value
                .withRevision(Some(
                  existing.itemRevision.fold(ItemRevision.Initial/*not expected*/)(_.next)))))
        })
  }

  private def unsignedSimpleItemToEvent(item: UnsignedSimpleItem)
  : Checked[UnsignedSimpleItemAddedOrChanged] =
    if (item.itemRevision.isDefined)
      Left(Problem.pure("ItemRevision is not accepted here"))
    else
      for (_ <- checkItem(item)) yield
        controllerState.pathToSimpleItem.get(item.key) match {
          case None =>
            UnsignedSimpleItemAdded(item.withRevision(Some(ItemRevision.Initial)))
          case Some(existing) =>
            UnsignedSimpleItemChanged(item
              .withRevision(Some(
                existing.itemRevision.fold(ItemRevision.Initial/*not expected*/)(_.next))))
        }

  private def simpleItemDeletionEvents(path: SimpleItemPath): View[BasicItemEvent] =
    path match {
      case path: InventoryItemPath.AssignableToAgent
        if controllerState.itemToAgentToAttachedState.contains(path) =>
          (!controllerState.deletionMarkedItems.contains(path) ? ItemDeletionMarked(path)).view ++
            controllerState.detach(path)

      case _ =>
        View(ItemDeleted(path))
    }
}

object VerifiedUpdateItemsExecutor
{
  def execute(
    verifiedUpdateItems: VerifiedUpdateItems,
    controllerState: ControllerState,
    checkItem: PartialFunction[InventoryItem, Checked[Unit]] = PartialFunction.empty)
  : Checked[Seq[KeyedEvent[NoKeyEvent]]] =
    new VerifiedUpdateItemsExecutor(
      verifiedUpdateItems,
      controllerState,
      checkItem.getOrElse(_, Checked.unit)
    ).executeVerifiedUpdateItems

  private def deleteRemovedVersionedItem(controllerState: ControllerState, path: VersionedItemPath): Option[KeyedEvent[ItemDeleted]] =
    controllerState.repo
      .pathToVersionToSignedItems(path)
      .tail.headOption
      // Now we have the overriden item
      .flatMap(_.maybeSignedItem)
      .map(_.value.id)
      .flatMap(itemId => !controllerState.isInUse(itemId) ? (NoKey <-: ItemDeleted(itemId)))

  private def checkVerifiedUpdateConsistency(
    verifiedUpdateItems: VerifiedUpdateItems,
    controllerState: ControllerState)
  : Checked[Unit] = {
    val newChecked = controllerState.checkAddedOrChangedItems(verifiedUpdateItems.addOrChangeKeys)
    val delSimpleChecked = controllerState
      .checkDeletedSimpleItems(verifiedUpdateItems.simple.delete.view)
    val delVersionedChecked = controllerState.checkRemovedVersionedItems(
        verifiedUpdateItems.maybeVersioned.view.flatMap(_.remove))
    newChecked
      .combineLeftOrRight(delSimpleChecked)
      .combineLeftOrRight(delVersionedChecked)
  }
}
