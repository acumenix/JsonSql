package com.mmalek.jsonSql.jsonParsing

import com.mmalek.jsonSql.extensions.StringOps._
import com.mmalek.jsonSql.jsonParsing.Types.CreatorArgument
import com.mmalek.jsonSql.jsonParsing.dataStructures.NodeKind._
import com.mmalek.jsonSql.jsonParsing.dataStructures._
import com.mmalek.jsonSql.jsonParsing.fsm.State._
import com.mmalek.jsonSql.jsonParsing.fsm.{State, StateMachine}
import shapeless.Poly1

import scala.language.postfixOps

object JsonParser {
  def getJson(input: String): JValue = {
    val seed = getSeed
    val ParsingTuple(_, tree, _, _, _) = Normalizer
      .normalize(input)
      .foldLeft(seed)((aggregate, char) => {
        aggregate.stateMachine.next(char, aggregate.builder, aggregate.statesHistory).map(sm => {
          val actionTuple = getActionTuple(aggregate.stateMachine.state, aggregate.builder)

          aggregate.builder.clear()
          aggregate.builder.append(char)

          val nextHistory = sm.state :: aggregate.statesHistory.toList

          createParsingTuple(sm, aggregate, nextHistory, actionTuple)
        }).getOrElse({
          aggregate.builder.append(char)
          aggregate
        })
      })

    tree.execute
  }

  private def getSeed =
    ParsingTuple(
      new StateMachine(State.Initial),
      FunctionsTree.zero,
      Nil,
      new StringBuilder,
      Nil)

  private def getActionTuple(state: State, sb: StringBuilder) =
    state match {
      case ReadObjectEnd | ReadArrayEnd => (Navigation.Up, NoneNode, None)
      case ReadObject => (Navigation.Down, ObjectNode, Some(getObject))
      case ReadObjectKey => (Navigation.Down, KeyNode(sb.toString), Some(getPropertyKey(getCleanedPropertyKey(sb))))
      case ReadArray => (Navigation.Down, ArrayNode, Some(getArray))
      case ReadScalar => (Navigation.Up, ScalarNode(sb.toString), Some(getScalar(getCleanedScalar(sb))))
      case Initial => (Navigation.Stay, NoneNode, None)
      case _ => (Navigation.Stay, KeyNode(sb.toString), None)
    }

  private def createParsingTuple(sm: StateMachine,
                                 aggregate: ParsingTuple,
                                 history: Seq[State],
                                 actionTuple: (Navigation, NodeKind, Option[CreatorArgument => JValue])) = {
    val (navigation, childKind, f) = actionTuple
    val newTree = f.map(aggregate.tree.addChild(childKind, _, aggregate.currentTreePath)).getOrElse(aggregate.tree)
    val rightmostPath = newTree.getRightmostChildPath()
    val oldPath = updatePathObjects(aggregate.currentTreePath, rightmostPath)
    val path =
      if (shouldPopTwo(oldPath) && navigation == Navigation.Up) oldPath.init.init
      else if (navigation == Navigation.Up && oldPath.nonEmpty) oldPath.init
      else if (navigation == Navigation.Stay) oldPath
      else rightmostPath

    ParsingTuple(sm, newTree, path, aggregate.builder, history)
  }

  private def shouldPopTwo(oldPath: List[Node]) = {
    if (oldPath.length >= 2)
      (oldPath.lastOption.exists(_.kind == ObjectNode) || oldPath.lastOption.exists(_.kind == ArrayNode)) && oldPath.init.lastOption.exists(n => n.kind.isInstanceOf[KeyNode])
    else false
  }

  private def updatePathObjects(currentTreePath: Seq[Node], newObjects: Seq[Node]) =
    currentTreePath
      .foldLeft((List.empty[Node], newObjects))((pair, _) => pair match {
        case (newPath, updater) => (newPath :+ updater.head, updater.tail)
      })
      ._1

  private def getCleanedPropertyKey(sb: StringBuilder) = {
    val value = sb.toString
    val firstQuoteIdx = value.indexOf('"')
    val secondQuoteIdx = value.length - value.reverse.indexOf('"')

    value.substring(firstQuoteIdx + 1, secondQuoteIdx - 1)
  }

  private def getCleanedScalar(sb: StringBuilder) = {
    val value = sb.toString
    val firstQuoteIdx = value.indexOf('"')
    val secondQuoteIdx = value.length - value.reverse.indexOf('"')

    if (firstQuoteIdx < 0) value.trim
    else value.substring(firstQuoteIdx + 1, secondQuoteIdx - 1)
  }

  private val getPropertyKey = (name: String) =>
    (arg: CreatorArgument) => JField(name, arg.fold(CreatorArgumentToJValue))

  private val getScalar = (scalar: String) =>
    (_: CreatorArgument) => scalar.asJValue

  private val getObject = (arg: CreatorArgument) =>
    arg.select[JObject] match {
      case None => JNull
      case Some(obj) => obj
    }

  private val getArray = (arg: CreatorArgument) =>
    arg.select[JArray] match {
      case None => JNull
      case Some(array) => array
    }

  object CreatorArgumentToJValue extends Poly1 {
    implicit val atJString: Case.Aux[JString, JValue] = at { x: JString => x }
    implicit val atJInt: Case.Aux[JNumber, JValue] = at { x: JNumber => x }
    implicit val atJBool: Case.Aux[JBool, JValue] = at { x: JBool => x }
    implicit val atFields: Case.Aux[JObject, JValue] = at { x: JObject => x }
    implicit val atValues: Case.Aux[JArray, JValue] = at { x: JArray => x }
    implicit val atUnit: Case.Aux[Unit, JValue] = at { _: Unit => JNull }
  }

  private case class ParsingTuple(stateMachine: StateMachine,
                                  tree: FunctionsTree,
                                  currentTreePath: Seq[Node],
                                  builder: StringBuilder,
                                  statesHistory: Seq[State])

}
