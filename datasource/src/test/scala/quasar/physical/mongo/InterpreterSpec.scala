/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.mongo

import slamdata.Predef._

import cats.effect.IO

import org.bson.{Document => _, _}
import org.mongodb.scala._
import org.specs2.matcher.MatchResult

import qdata.{QData, QDataDecode}
import quasar.common.data.RValue
import quasar.common.CPath
import quasar.{EffectfulQSpec, IdStatus, ParseInstruction, ParseType}

import fs2.Stream

import shims._
import testImplicits._

import MongoResource._
import RValue._

class InterpreterSpec extends EffectfulQSpec[IO] {
  val E = MongoExpression
  import InterpreterSpec._

  import MongoSpec.mkMongo


  private def check(
      input: List[E.Object],
      idStatus: IdStatus,
      instructions: List[ParseInstruction],
      expected: (List[ParseInstruction], List[RValue]))
      : IO[MatchResult[(List[ParseInstruction], List[RValue])]] =

  mkMongo.flatMap(_ { mongo => for {
    c <- uniqueCollection
    _ <- dropCollection(c, mongo)
    _ <- insertValues(c, mongo, input)
    actual <- mongo.evaluate(c, idStatus, instructions) flatMap {
      case (insts, bsons) => toRValueList(bsons) map (x => (insts, x))
    }
    _ <- dropCollection(c, mongo)
  } yield actual === expected })

  val basicInput: List[E.Object] =
    List(
      E.Object("_id" -> E.String("0"), "foo" -> E.String("foo")),
      E.Object("_id" -> E.String("1"), "foo" -> E.String("bar")),
      E.Object("_id" -> E.String("2"), "foo" -> E.String("baz")))

  "Check reading without instructions" >> {
    "ExcludeId" >>* check(
      basicInput,
      IdStatus.ExcludeId,
      List(),
      (List(), List(
        rObject(Map("_id" -> rString("0"), "foo" -> rString("foo"))),
        rObject(Map("_id" -> rString("1"), "foo" -> rString("bar"))),
        rObject(Map("_id" -> rString("2"), "foo" -> rString("baz"))))))

    "IdOnly" >>* check(
      basicInput,
      IdStatus.IdOnly,
      List(),
      (List(), List(rString("0"), rString("1"), rString("2"))))

    "IncludeId" >>* check(
      basicInput,
      IdStatus.IncludeId,
      List(),
      (List(), List(
        rArray(List(rString("0"), rObject(Map("_id" -> rString("0"), "foo" -> rString("foo"))))),
        rArray(List(rString("1"), rObject(Map("_id" -> rString("1"), "foo" -> rString("bar"))))),
        rArray(List(rString("2"), rObject(Map("_id" -> rString("2"), "foo" -> rString("baz"))))))))
  }

  "Wrap only" >> {
    "ExcludeId" >>* check(
      basicInput,
      IdStatus.ExcludeId,
      List(ParseInstruction.Wrap("wrapper")),
      (List(), List(
        rObject(Map("wrapper" -> rObject(Map("_id" -> rString("0"), "foo" -> rString("foo"))))),
        rObject(Map("wrapper" -> rObject(Map("_id" -> rString("1"), "foo" -> rString("bar"))))),
        rObject(Map("wrapper" -> rObject(Map("_id" -> rString("2"), "foo" -> rString("baz"))))))))

    "IncludeId" >>* check(
      basicInput,
      IdStatus.IncludeId,
      List(ParseInstruction.Wrap("wrapper")),
      (List(), List(
        rObject(Map("wrapper" -> rArray(List(rString("0"), rObject(Map("_id" -> rString("0"), "foo" -> rString("foo"))))))),
        rObject(Map("wrapper" -> rArray(List(rString("1"), rObject(Map("_id" -> rString("1"), "foo" -> rString("bar"))))))),
        rObject(Map("wrapper" -> rArray(List(rString("2"), rObject(Map("_id" -> rString("2"), "foo" -> rString("baz"))))))))))
  }

  val simpleArrays = List(
    E.Object("_id" -> E.String("0"), "arr" -> E.Array(E.String("a"), E.String("b"), E.String("c"))),
    E.Object("_id" -> E.String("1"), "arr" -> E.Array(E.String("d"), E.String("e"), E.String("f"))))

  "project" >>* check(
    simpleArrays,
    IdStatus.ExcludeId,
    List(ParseInstruction.Project(CPath.parse(".arr"))),
    (List(), List(
      rArray(List(rString("a"), rString("b"), rString("c"))),
      rArray(List(rString("d"), rString("e"), rString("f"))))))

  "project and pivot" >>* check(
    simpleArrays,
    IdStatus.ExcludeId,
    List(ParseInstruction.Project(CPath.parse(".arr")), ParseInstruction.Pivot(IdStatus.IncludeId, ParseType.Array)),
    (List(), List(
      rArray(List(rLong(0L), rString("a"))),
      rArray(List(rLong(1L), rString("b"))),
      rArray(List(rLong(2L), rString("c"))),
      rArray(List(rLong(0L), rString("d"))),
      rArray(List(rLong(1L), rString("e"))),
      rArray(List(rLong(2L), rString("f"))))))

}

object InterpreterSpec {
  val E = MongoExpression

  def mkCollection(name: String): Collection = Collection(Database("aggregation_test"), name)

  def dropCollection(collection: Collection, mongo: Mongo[IO]): IO[Unit] = {
    Mongo.singleObservableAsF[IO, Completed](mongo.getCollection(collection).drop())
      .attempt
      .map(_ => ())
  }

  def insertValues(collection: Collection, mongo: Mongo[IO], vals: List[E.Object]): IO[Unit] = {
    Mongo.singleObservableAsF[IO, Completed](
      mongo.getCollection(collection) insertMany (vals map { x => Document(x.toBsonValue) })) map (_ => ())
  }

  def toRValueList(stream: Stream[IO, BsonValue]): IO[List[RValue]] = {
    implicit val bsonValueQDataDecode: QDataDecode[BsonValue] = decoder.qdataDecoder
    stream.compile.toList map { _ map { QData.convert[BsonValue, RValue] } }
  }

  val uniqueCollection: IO[Collection] =
    IO.delay(java.util.UUID.randomUUID().toString) map mkCollection
}