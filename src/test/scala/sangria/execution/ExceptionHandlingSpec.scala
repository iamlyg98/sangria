package sangria.execution

import org.scalatest.{Matchers, WordSpec}
import sangria.parser.QueryParser
import sangria.schema._
import sangria.util.{DebugUtil, FutureResultSupport, OutputMatchers, StringMatchers}
import sangria.validation.{AstNodeLocation, BadValueViolation, BaseViolation, UndefinedFieldViolation}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

class ExceptionHandlingSpec extends WordSpec with Matchers with FutureResultSupport with OutputMatchers with StringMatchers {

  case object EmailTypeViolation extends BaseViolation("Invalid email")

  val errorScalar = ScalarAlias[String, String](StringType,
    toScalar = identity,
    fromScalar = _ ⇒ Left(EmailTypeViolation))

  val TestType = ObjectType("Test", fields[Unit, Unit](
    Field("success", OptionType(StringType),
      arguments = Argument("num", OptionInputType(IntType)) :: Nil,
      resolve = _ ⇒ "Yay"),
    Field("errorInScalar", OptionType(StringType),
      arguments = Argument("email", errorScalar) :: Nil,
      resolve = _ ⇒ "Yay"),
    Field("trySuccess", OptionType(StringType), resolve = _ ⇒ Success("try!")),
    Field("tryError", OptionType(StringType), resolve = _ ⇒ Failure(new IllegalStateException("try boom!"))),
    Field("error", OptionType(StringType), resolve = _ ⇒ throw new IllegalStateException("Boom!")),
    Field("futureError", OptionType(StringType), resolve = _ ⇒ Future.failed[String](new IllegalStateException("Boom!")))
  ))

  val schema = Schema(TestType)

  "Exception handling" should {
    "obfuscate unexpected exceptions" in {
      val out = captureStdErr {
        val Success(doc) = QueryParser.parse("""
        {
          success
          tryError
          trySuccess
          error
          futureError
        }
        """)

        Executor.execute(schema, doc).await should be  (
          Map(
            "data" → Map(
              "success" → "Yay",
              "trySuccess" → "try!",
              "tryError" → null,
              "error" → null,
              "futureError" → null),
            "errors" → List(
              Map(
                "message" → "Internal server error",
                "path" → List("error"),
                "locations" → List(Map("line" → 6, "column" → 11))),
              Map(
                "message" → "Internal server error",
                "path" → List("tryError"),
                "locations" → List(Map("line" → 4, "column" → 11))),
              Map(
                "message" → "Internal server error",
                "path" → List("futureError"),
                "locations" → List(Map("line" → 7, "column" → 11))))))
      }

      out should include ("java.lang.IllegalStateException: Boom!")
    }

    "provide user-defined exception handling mechanism" in {
      val Success(doc) = QueryParser.parse("""
        {
          error
          futureError
        }
        """)

      val exceptionHandler = ExceptionHandler {
        case (m, e: IllegalStateException) ⇒ HandledException(e.getMessage)
      }

      Executor.execute(schema, doc, exceptionHandler = exceptionHandler).await should be  (
        Map(
          "data" → Map(
            "error" → null,
            "futureError" → null),
          "errors" → List(
            Map(
              "message" → "Boom!",
              "path" → List("error"),
              "locations" → List(Map("line" → 3, "column" → 11))),
            Map(
              "message" → "Boom!",
              "path" → List("futureError"),
              "locations" → List(Map("line" → 4, "column" → 11))))))
    }

    "provide user-defined exception handling mechanism which allows to provide additional fields" in {
      val Success(doc) = QueryParser.parse("""
        {
          error
          futureError
        }
        """)

      val exceptionHandler = ExceptionHandler {
        case (m, e: IllegalStateException) ⇒
          HandledException(e.getMessage,
            Map("foo" → m.arrayNode(Vector(m.scalarNode("bar", "String", Set.empty), m.scalarNode(1234, "Int", Set.empty))), "baz" → m.scalarNode("Test", "String", Set.empty)))
      }

      Executor.execute(schema, doc, exceptionHandler = exceptionHandler).await should be  (
        Map(
          "data" → Map(
            "error" → null,
            "futureError" → null),
          "errors" → List(
            Map(
              "message" → "Boom!",
              "path" → List("error"),
              "foo" → List("bar", 1234),
              "baz" → "Test",
              "locations" → List(Map("line" → 3, "column" → 11))),
            Map(
              "message" → "Boom!",
              "path" → List("futureError"),
              "foo" → List("bar", 1234),
              "baz" → "Test",
              "locations" → List(Map("line" → 4, "column" → 11))))))
    }

    "handle violation-based errors" in {
      val Success(doc) = QueryParser.parse("""
        {
          nonExistingField
          success(num: "One")
          errorInScalar(email: "foo")
        }
        """)

      val exceptionHandler = ExceptionHandler (onViolation = {
        case (m, BadValueViolation(_, _, Some(v: EmailTypeViolation.type), _, _)) ⇒
          HandledException("Scalar", Map("original" → m.scalarNode(v.errorMessage, "String", Set.empty)))
        case (m, v: UndefinedFieldViolation) ⇒
          HandledException("Field is missing!!! D:", Map("fieldName" → m.scalarNode(v.fieldName, "String", Set.empty)))
        case (_, v: AstNodeLocation) ⇒
          HandledException(v.simpleErrorMessage + " [with extras]")
      })

      val res =
        Executor.execute(schema, doc, exceptionHandler = exceptionHandler).recover {
          case analysis: QueryAnalysisError ⇒ analysis.resolveError
        }
      
      res.await should be  (
        Map(
          "data" → null,
          "errors" → Vector(
            Map(
              "message" → "Field is missing!!! D:",
              "locations" → Vector(Map("line" → 3, "column" → 11)),
              "fieldName" → "nonExistingField"),
            Map(
              "message" → "Expected type 'Int', found '\"One\"'. Int value expected [with extras]",
              "locations" → Vector(Map("line" → 4, "column" → 24))),
            Map(
              "message" → "Scalar",
              "locations" → Vector(Map("line" → 5, "column" → 32)),
              "original" → "Invalid email"))))
    }

    "handle user-facing errors errors" in {
      val Success(doc) = QueryParser.parse("""
        query Foo {
          success(num: 1)
        }
        """)

      val exceptionHandler = ExceptionHandler (onUserFacingError = {
        case (m, e: OperationSelectionError) ⇒
          HandledException("Wrong operation?!", Map("errorCode" → m.scalarNode("AAAAAaaAA!", "String", Set.empty)))
      })

      val res =
        Executor.execute(schema, doc, operationName = Some("Bar"), exceptionHandler = exceptionHandler).recover {
          case analysis: QueryAnalysisError ⇒ analysis.resolveError
        }

      res.await should be  (
        Map(
          "data" → null,
          "errors" → Vector(
            Map(
              "message" → "Wrong operation?!",
              "errorCode" → "AAAAAaaAA!"))))
    }

    "allow multiple handled errors with ast positions" in {
      val Success(doc) = QueryParser.parse("""
        query Foo {
          error
        }
        """.stripCR)

      val exceptionHandler = ExceptionHandler {
        case (m, e: IllegalStateException) ⇒
          HandledException(
            Vector(
              ("Error 1", Map("errorCode" → m.scalarNode("OOPS", "String", Set.empty)), Nil),
              ("Error 2", Map.empty[String, m.Node], doc.operations.head._2.location.toList)))
      }

      Executor.execute(schema, doc, exceptionHandler = exceptionHandler).await should be  (
        Map(
          "data" → Map("error" → null),
          "errors" → Vector(
            Map(
              "message" → "Error 1",
              "path" → Vector("error"),
              "locations" → Vector(
                Map("line" → 3, "column" → 11)),
              "errorCode" → "OOPS"),
            Map(
              "message" → "Error 2",
              "path" → Vector("error"),
              "locations" → Vector(
                Map("line" → 3, "column" → 11),
                Map("line" → 2, "column" → 9))))))
    }
  }

}
