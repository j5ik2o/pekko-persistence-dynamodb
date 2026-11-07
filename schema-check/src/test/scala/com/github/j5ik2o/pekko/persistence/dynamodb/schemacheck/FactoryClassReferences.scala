package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import scala.collection.mutable
import scala.util.matching.Regex

final case class Definition(parent: String, name: String)

final case class Reference(fqcn: String, source: String)

object FactoryClassReferences {

  private val ReferencePattern: Regex = "\"(com\\.github\\.j5ik2o\\.[A-Za-z0-9_.$]+)\"".r

  private val TokenPattern: Regex = """[A-Za-z_][A-Za-z0-9_]*|[{}()\[\].,;=:]|\n""".r

  private val ReservedWords: Set[String] = Set(
    "package",
    "import",
    "object",
    "class",
    "trait",
    "extends",
    "with",
    "type",
    "val",
    "var",
    "def",
    "private",
    "protected",
    "final",
    "sealed",
    "abstract",
    "case",
    "override",
    "implicit",
    "lazy",
    "new",
    "return",
    "if",
    "else",
    "while",
    "for",
    "match",
    "try",
    "catch",
    "finally",
    "throw",
    "yield"
  )

  def references(repo: Repo): Seq[Reference] = {
    val scalaReferences = repo.files(".scala").flatMap { path =>
      ReferencePattern.findAllMatchIn(repo.read(path)).map(matched => Reference(matched.group(1), repo.relative(path)))
    }
    val confReferences = repo.files(".conf").flatMap { path =>
      ReferencePattern.findAllMatchIn(repo.read(path)).map(matched => Reference(matched.group(1), repo.relative(path)))
    }
    (scalaReferences ++ confReferences).sortBy(reference => (reference.fqcn, reference.source))
  }

  def definitions(repo: Repo): Map[String, Set[String]] = {
    val pairs = repo.files(".scala").flatMap(path => definitionsInSource(repo.read(path)))
    pairs
      .groupBy { case Definition(parent, _) => parent }
      .map { case (parent, found) => parent -> found.map { case Definition(_, name) => name }.toSet }
  }

  def resolve(definitions: Map[String, Set[String]], fqcn: String): Boolean = {
    val segments = fqcn.split("\\$").toVector
    val first    = segments.head
    val dot      = first.lastIndexOf('.')
    if (dot <= 0) {
      false
    } else {
      val packagePath = first.substring(0, dot)
      val topName     = first.substring(dot + 1)
      val names       = topName +: segments.tail
      var scope       = packagePath
      var found       = true
      names.foreach { name =>
        if (found) {
          if (definitions.getOrElse(scope, Set.empty).contains(name)) {
            scope = scope + "$" + name
          } else {
            found = false
          }
        }
      }
      found
    }
  }

  // Tracks package and type scopes from the brace structure, so a nested object resolves as Outer$Inner.
  def definitionsInSource(source: String): Set[Definition] = {
    val tokens = TokenPattern.findAllIn(withoutCommentsAndStrings(source)).toVector
    val result = mutable.Set.empty[Definition]

    var i               = 0
    var braceDepth      = 0
    var parenDepth      = 0
    var bracketDepth    = 0
    var packagePath     = ""
    var typeScopes      = List.empty[(Int, String)]
    var packageScopes   = List.empty[(Int, String, String)]
    var pendingDef      = Option.empty[String]
    var pendingPackage  = Option.empty[String]
    var lastSignificant = ""

    def nextSignificantIndex(from: Int): Option[Int] = {
      var index = from
      while (index < tokens.length && tokens(index) == "\n") index += 1
      if (index < tokens.length) Some(index) else None
    }

    def nextSignificant(from: Int): Option[String] =
      nextSignificantIndex(from).map(tokens(_))

    def currentParent: String = {
      if (typeScopes.isEmpty) packagePath
      else if (packagePath.isEmpty) typeScopes.reverse.map { case (_, name) => name }.mkString("$")
      else packagePath + "$" + typeScopes.reverse.map { case (_, name) => name }.mkString("$")
    }

    def readDottedName(start: Int): (Vector[String], Int) = {
      val segments    = Vector.newBuilder[String]
      var index       = start
      var expecting   = true
      var keepReading = true
      while (keepReading && index < tokens.length) {
        val token = tokens(index)
        if (expecting) {
          if (isIdentifier(token) && !ReservedWords.contains(token)) {
            segments += token
            index += 1
            expecting = false
          } else {
            keepReading = false
          }
        } else if (token == ".") {
          index += 1
          expecting = true
        } else {
          keepReading = false
        }
      }
      (segments.result(), index)
    }

    while (i < tokens.length) {
      val token = tokens(i)
      token match {
        case "\n" =>
          if (pendingDef.isDefined && parenDepth == 0 && bracketDepth == 0) {
            val continues =
              nextSignificant(i + 1).exists(value => value == "{" || value == "extends" || value == "with") ||
              lastSignificant == "extends" || lastSignificant == "with" ||
              lastSignificant == "," || lastSignificant == "(" || lastSignificant == "[" || lastSignificant == ":"
            if (!continues) pendingDef = None
          }
          i += 1

        case "package" if tokens.lift(i + 1).contains("object") =>
          nextSignificantIndex(i + 2) match {
            case Some(nameIndex) =>
              pendingPackage = Some(joinPath(packagePath, tokens(nameIndex)))
              i = nameIndex + 1
            case None =>
              i = tokens.length
          }

        case "package" =>
          val (segments, endExclusive) = readDottedName(i + 1)
          if (segments.isEmpty) {
            i += 1
          } else {
            nextSignificantIndex(endExclusive) match {
              case Some(index) if tokens(index) == "{" =>
                pendingPackage = Some(joinPath(packagePath, segments.mkString(".")))
                i = index
              case _ =>
                packagePath = segments.mkString(".")
                i = endExclusive
            }
          }

        case "class" | "trait" | "object" =>
          nextSignificantIndex(i + 1) match {
            case Some(nameIndex) if isIdentifier(tokens(nameIndex)) =>
              result += Definition(currentParent, tokens(nameIndex))
              pendingDef = Some(tokens(nameIndex))
            case _ =>
          }
          i += 1

        case "{" =>
          (pendingPackage, pendingDef) match {
            case (Some(packageName), _) if parenDepth == 0 && bracketDepth == 0 =>
              braceDepth += 1
              packageScopes = (braceDepth, packageName, packagePath) :: packageScopes
              packagePath = packageName
              pendingPackage = None
            case (_, Some(definitionName)) if parenDepth == 0 && bracketDepth == 0 =>
              braceDepth += 1
              typeScopes = (braceDepth, definitionName) :: typeScopes
              pendingDef = None
            case _ =>
              braceDepth += 1
          }
          i += 1

        case "}" =>
          var keepPopping = true
          while (keepPopping) {
            packageScopes.headOption match {
              case Some((depth, _, previous)) if depth == braceDepth =>
                packagePath = previous
                packageScopes = packageScopes.tail
              case _ =>
                keepPopping = false
            }
          }
          keepPopping = true
          while (keepPopping) {
            typeScopes.headOption match {
              case Some((depth, _)) if depth == braceDepth =>
                typeScopes = typeScopes.tail
              case _ =>
                keepPopping = false
            }
          }
          braceDepth -= 1
          if (parenDepth == 0 && bracketDepth == 0) pendingDef = None
          i += 1

        case "(" =>
          parenDepth += 1
          i += 1

        case ")" =>
          parenDepth = math.max(0, parenDepth - 1)
          i += 1

        case "[" =>
          bracketDepth += 1
          i += 1

        case "]" =>
          bracketDepth = math.max(0, bracketDepth - 1)
          i += 1

        case ";" =>
          if (parenDepth == 0 && bracketDepth == 0) pendingDef = None
          i += 1

        case _ =>
          i += 1
      }
      if (token != "\n") lastSignificant = token
    }

    result.toSet
  }

  private def joinPath(base: String, name: String): String =
    if (base.isEmpty) name else base + "." + name

  private def isIdentifier(token: String): Boolean =
    token.nonEmpty && (token.head.isLetter || token.head == '_') &&
    token.forall(character => character.isLetterOrDigit || character == '_')

  // Drops comments and string literals so keywords inside them are not read as declarations.
  private def withoutCommentsAndStrings(source: String): String = {
    val out        = new StringBuilder(source.length)
    var i          = 0
    var blockDepth = 0
    while (i < source.length) {
      val character = source.charAt(i)
      if (blockDepth > 0) {
        if (character == '/' && i + 1 < source.length && source.charAt(i + 1) == '*') {
          blockDepth += 1
          i += 2
        } else if (character == '*' && i + 1 < source.length && source.charAt(i + 1) == '/') {
          blockDepth -= 1
          i += 2
        } else {
          i += 1
        }
      } else if (character == '/' && i + 1 < source.length && source.charAt(i + 1) == '/') {
        i += 2
        while (i < source.length && source.charAt(i) != '\n') i += 1
      } else if (character == '/' && i + 1 < source.length && source.charAt(i + 1) == '*') {
        blockDepth += 1
        i += 2
      } else if (
        character == '"' && i + 2 < source.length && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"'
      ) {
        i += 3
        var closed = false
        while (i < source.length && !closed) {
          if (
            source.charAt(i) == '"' && i + 2 < source.length && source.charAt(i + 1) == '"' &&
            source.charAt(i + 2) == '"'
          ) {
            i += 3
            closed = true
          } else {
            i += 1
          }
        }
        out.append(' ')
      } else if (character == '"') {
        i += 1
        var closed = false
        while (i < source.length && !closed) {
          val current = source.charAt(i)
          if (current == '\\') {
            i += 2
          } else if (current == '"') {
            i += 1
            closed = true
          } else {
            i += 1
          }
        }
        out.append(' ')
      } else {
        out.append(character)
        i += 1
      }
    }
    out.toString()
  }
}
