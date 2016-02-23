import scala.concurrent._
import scala.util.{ Try, Failure }

import reactivemongo.api._
import reactivemongo.bson._
import reactivemongo.api.commands.Command
import reactivemongo.api.commands.bson._
import BSONCommonWriteCommandsImplicits._
import BSONInsertCommand._
import BSONInsertCommandImplicits._
import BSONCountCommand._
import BSONCountCommandImplicits._
import BSONIsMasterCommand._
import BSONIsMasterCommandImplicits._

object QueryAndWriteCommands extends org.specs2.mutable.Specification {
  import Common._

  sequential

  lazy val collection = db("queryandwritecommandsspec")

  "ReactiveMongo" should {
    "insert 1 doc and retrieve it" in {
      val doc = BSONDocument("_id" -> BSONNull, "name" -> "jack", "plop" -> -1)
      val lastError = Await.result(Command.run(BSONSerializationPack)(collection, Insert(doc, doc)), timeout)
      //println(lastError)
      lastError.ok mustEqual true

      val found = Await.result(collection.find(doc).cursor[BSONDocument]().collect[List](), timeout)
      found.size mustEqual 1
      val count = Await.result(Command.run(BSONSerializationPack).unboxed(collection, Count(BSONDocument())), timeout)
      count mustEqual 1

      Command.run(BSONSerializationPack)(db, IsMaster).map(_ => {}).
        aka("isMaster") must beEqualTo({}).await(timeoutMillis)
    }

    "insert 1 doc with collection.insert and retrieve it" in {
      val doc = BSONDocument("name" -> "joe", "plop" -> -2)

      collection.insert(doc).map(_.ok) must beTrue.await(timeoutMillis) and {
        collection.find(doc).cursor[BSONDocument]().collect[List]().map(_.size).
          aka("result count") must beEqualTo(1).await(timeoutMillis)
      }
    }
  }

  import reactivemongo.api.collections.bson._

  val nDocs = 1000000
  "ReactiveMongo with new colls" should {
    "insert 1 doc and retrieve it" in {
      val coll = BSONCollection(db, "queryandwritecommandsspec", collection.failoverStrategy)
      val doc = BSONDocument("name" -> "Stephane")
      val lastError = Await.result(coll.insert(doc), timeout)
      //println(lastError)
      lastError.ok mustEqual true

      coll.find(doc).cursor[BSONDocument]().collect[List]().map(_.size).
        aka("result size") must beEqualTo(1).await(timeoutMillis)
    }

    s"insert $nDocs in bulks (including 3 duplicate errors)" in {
      import reactivemongo.api.indexes._
      import reactivemongo.api.indexes.IndexType.Ascending

      val created = collection.indexesManager.
        ensure(Index(List("plop" -> Ascending), unique = true))
      Await.result(created, timeout)

      val coll = BSONCollection(db, "queryandwritecommandsspec", collection.failoverStrategy)
      val docs = (0 until nDocs).toStream.map { i =>
        if (i == 0 || i == 1529 || i == 3026 || i == 19862) {
          BSONDocument("bulk" -> true, "i" -> i, "plop" -> -3)
        } else BSONDocument("bulk" -> true, "i" -> i, "plop" -> i)
      }

      coll.bulkInsert(docs, false).map(_ => {}).
        aka("bulk insert") must beEqualTo({}).await(100000 /* 100s */ ) and {
          Command.run(BSONSerializationPack).unboxed(
            coll, Count(BSONDocument("bulk" -> true))).
            aka("count") must beEqualTo(nDocs - 3).await(timeoutMillis)
          // all docs minus errors
        }
    }
  }
}
