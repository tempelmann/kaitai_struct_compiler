package io.kaitai.struct.languages

import io.kaitai.struct._
import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.datatype.{CalcEndian, DataType, FixedEndian, InheritedEndian}
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.format._
import io.kaitai.struct.languages.components._
import io.kaitai.struct.translators.{CppTranslator, TypeDetector}

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Stack

class CppCompiler(
  typeProvider: ClassTypeProvider,
  config: RuntimeConfig
) extends LanguageCompiler(typeProvider, config)
    with ObjectOrientedLanguage
    with AllocateAndStoreIO
    with FixedContentsUsingArrayByteLiteral
    with UniversalDoc
    with EveryReadIsExpression {
  import CppCompiler._

  // Collect imports
  private val importListSrc = new ImportList
  private val importListHdr = new ImportList

  override val translator = new CppTranslator(typeProvider, importListSrc)
  private val outSrcHeader = new StringLanguageOutputWriter(indent)
  private val outHdrHeader = new StringLanguageOutputWriter(indent)
  private var outSrc = new StringLanguageOutputWriter(indent)
  private val outHdr = new StringLanguageOutputWriter(indent)

  private class StringList {
    private val list = ListBuffer[String]()
    def addUnique(s: String) = Utils.addUniqueAttr(list, s)
    def add(s: String) = list += s
    def toList: List[String] = list.toList
    def clear() = list.clear()
  }

  // Collect public and private attributes:
  private var publicDecls = new StringList
  private var privateDecls = new StringList
  private val declsStack = new Stack[StringList]

  // Remember our previous outSrc when redirecting it to a temporary writer
  private var srcStack = new Stack[StringLanguageOutputWriter]

  // Collect lines for constructor, as we output them later, in classFooter()
  private var constructorPrefixWriter = () => {}

  // Collect attributes that needs to be initalized in the constructor:
  private var constructorInitializers = new StringList

  override def results(topClass: ClassSpec): Map[String, String] = {
    val fn = topClass.nameAsStr
    Map(
      s"$fn.cpp" -> (outSrcHeader.result + importListToStr(importListSrc) + outSrc.result),
      s"$fn.h" -> (outHdrHeader.result + importListToStr(importListHdr) + outHdr.result)
    )
  }

  private def importListToStr(importList: ImportList): String =
    importList.toList.map((x) => s"#include <$x>").mkString("", "\n", "\n")

  override def indent: String = "    "

  private def halfIndent: String = {
    indent.substring(indent.length()/2)
  }

  override def outFileName(topClassName: String): String = topClassName

  override def fileHeader(topClassName: String): Unit = {
    outSrcHeader.puts(s"// $headerComment")
    outSrcHeader.puts
    outSrcHeader.puts("#include \"" + outFileName(topClassName) + ".h\"")
    outSrcHeader.puts

    outHdrHeader.puts(s"#ifndef ${defineName(topClassName)}")
    outHdrHeader.puts(s"#define ${defineName(topClassName)}")
    outHdrHeader.puts
    outHdrHeader.puts(s"// $headerComment")
    outHdrHeader.puts
    outHdrHeader.puts("#include \"kaitai/kaitaistruct.h\"")
    outHdrHeader.puts

    importListHdr.add("stdint.h")

    // API compatibility check
    val minVer = KSVersion.minimalRuntime.toInt
    outHdr.puts
    outHdr.puts(s"#if KAITAI_STRUCT_VERSION < ${minVer}L")
    outHdr.puts(
      "#error \"Incompatible Kaitai Struct C++/STL API: version " +
        KSVersion.minimalRuntime + " or later is required\""
    )
    outHdr.puts("#endif")
  }

  override def fileFooter(topClassName: String): Unit = {
    outHdr.puts
    outHdr.puts(s"#endif  // ${defineName(topClassName)}")
  }

  override def opaqueClassDeclaration(classSpec: ClassSpec): Unit = {
    classForwardDeclaration(classSpec.name)
    outSrc.puts("#include \"" + outFileName(classSpec.name.head) + ".h\"")
  }

  override def classHeader(name: List[String]): Unit = {

    declsStack.push(privateDecls)
    declsStack.push(publicDecls)
    privateDecls = new StringList
    publicDecls = new StringList

    outHdr.puts
    outHdr.puts(s"class ${types2class(List(name.last))} : public $kstructName {")
    outHdr.puts(halfIndent + "public:")
    outHdr.inc

    /*
    outHdr.puts(s"static ${type2class(name)} from_file(std::string ${attrReaderName("file_name")});")

    outSrc.puts
    outSrc.puts(s"${type2class(name)} ${type2class(name)}::from_file(std::string ${attrReaderName("file_name")}) {")
    outSrc.inc
    outSrc.puts("std::ifstream* ifs = new std::ifstream(file_name, std::ifstream::binary);")
    outSrc.puts("kaitai::kstream *ks = new kaitai::kstream(ifs);")
    outSrc.puts(s"return new ${type2class(name)}(ks);")
    outSrc.dec
    outSrc.puts("}")
    */
  }

  override def classFooter(name: List[String]): Unit = {

    // Now we can finally output all the lines we temporarily collected since classConstructorHeader()
    val prevSrc = outSrc
    outSrc = srcStack.pop()

    // But first we output the constructor:
    //constructorPrefixWriter()

    outSrc.add(prevSrc)

    outHdr.puts
    publicDecls.toList.foreach((line) => outHdr.puts(line))
    outHdr.puts
    outHdr.dec
    outHdr.puts(halfIndent + "private:")
    outHdr.inc
    privateDecls.toList.foreach((line) => outHdr.puts(line))
    outHdr.dec
    outHdr.puts("};")

    publicDecls = declsStack.pop()
    privateDecls = declsStack.pop()
  }

  override def classForwardDeclaration(name: List[String]): Unit = {
    outHdr.puts(s"class ${types2class(name)};")
  }

  override def classConstructorHeader(name: List[String], parentType: DataType, rootClassName: List[String], isHybrid: Boolean, params: List[ParamDefSpec]): Unit = {
    val (endianSuffixHdr, endianSuffixSrc)  = if (isHybrid) {
      (", int p_is_le = -1", ", int p_is_le")
    } else {
      ("", "")
    }

    val paramsArg = Utils.join(params.map((p) =>
      s"${kaitaiType2NativeType(p.dataType)} ${paramName(p.id)}"
    ), "", ", ", ", ")

    // Parameter names
    val pIo = paramName(IoIdentifier)
    val pParent = paramName(ParentIdentifier)
    val pRoot = paramName(RootIdentifier)

    // Types
    val tIo = s"$kstreamName*"
    val tParent = kaitaiType2NativeType(parentType)
    val tRoot = s"${types2class(rootClassName)}*"

    // Constructor (header)
    outHdr.puts
    outHdr.puts(s"${types2class(List(name.last))}($paramsArg" +
      s"$tIo $pIo, " +
      s"$tParent $pParent = 0, " +
      s"$tRoot $pRoot = 0$endianSuffixHdr);"
    )

    //
    // prepare Constructor (.cpp)
    //

    // We need to postpone writing the constructor to the output because we need to collect pointer members first,
    // so that we can initialize them in the constructor. Therefore, we re-route outSrc to a temporary writer
    // and write it out in classFooter()
    srcStack.push(outSrc)
    outSrc = new StringLanguageOutputWriter(indent)

    // Prepare collection of all pointers we want to initialize
    constructorInitializers.clear()
    constructorInitializers.addUnique(s"$kstructName($pIo)")

    //
    constructorPrefixWriter = () => {
      val initializers = constructorInitializers.toList.mkString(", ")
      outSrc.puts
      outSrc.puts(s"${types2class(name)}::${types2class(List(name.last))}($paramsArg" +
        s"$tIo $pIo, " +
        s"$tParent $pParent, " +
        s"$tRoot $pRoot$endianSuffixSrc) : $initializers {"
      )
    }
    constructorPrefixWriter()
    outSrc.inc
    handleAssignmentSimple(ParentIdentifier, pParent)
    handleAssignmentSimple(RootIdentifier, if (name == rootClassName) {
      "this"
    } else {
      pRoot
    })

    // Store endian flag
    typeProvider.nowClass.meta.endian match {
      case Some(_: CalcEndian) | Some(InheritedEndian) =>
        privateDecls.addUnique("int m__is_le;")
        handleAssignmentSimple(EndianIdentifier, if (isHybrid) "p_is_le" else "-1")
      case _ =>
        // no _is_le variable
    }

    // Store parameters passed to us
    params.foreach((p) => handleAssignmentSimple(p.id, paramName(p.id)))
  }

  override def classConstructorFooter: Unit = {
    outSrc.dec
    outSrc.puts("}")
  }

  override def classDestructorHeader(name: List[String], parentType: DataType, topClassName: List[String]): Unit = {
    // Destructor (header)
    outHdr.puts(s"~${types2class(List(name.last))}();")

    // Destructor (.cpp)
    outSrc.puts
    outSrc.puts(s"${types2class(name)}::~${types2class(List(name.last))}() {")
    outSrc.inc
  }

  override def classDestructorFooter = classConstructorFooter

  override def runRead(): Unit = {
    outSrc.puts("_read();")
  }

  override def runReadCalc(): Unit = {
    outSrc.puts
    outSrc.puts("if (m__is_le == -1) {")
    outSrc.inc
    importListSrc.add("stdexcept")
    outSrc.puts("throw std::runtime_error(\"unable to decide on endianness\");")
    outSrc.dec
    outSrc.puts("} else if (m__is_le == 1) {")
    outSrc.inc
    outSrc.puts("_read_le();")
    outSrc.dec
    outSrc.puts("} else {")
    outSrc.inc
    outSrc.puts("_read_be();")
    outSrc.dec
    outSrc.puts("}")
  }

  override def readHeader(endian: Option[FixedEndian], isEmpty: Boolean): Unit = {
    val suffix = endian match {
      case Some(e) => s"_${e.toSuffix}"
      case None => ""
    }
    privateDecls.addUnique(s"void _read$suffix();")
    outSrc.puts
    outSrc.puts(s"void ${types2class(typeProvider.nowClass.name)}::_read$suffix() {")
    outSrc.inc
  }

  override def readFooter(): Unit = {
    outSrc.dec
    outSrc.puts("}")
  }

  override def attributeDeclaration(attrName: Identifier, attrType: DataType, isNullable: Boolean): Unit = {
    privateDecls.addUnique(s"${kaitaiType2NativeType(attrType)} ${privateMemberName(attrName)};")
    declareNullFlag(attrName, isNullable)
  }

  override def attributeReader(attrName: Identifier, attrType: DataType, isNullable: Boolean): Unit = {
    publicDecls.addUnique(s"${kaitaiType2NativeType(attrType)} ${publicMemberName(attrName)}() const { return ${privateMemberName(attrName)}; }")
  }

  override def universalDoc(doc: DocSpec): Unit = {
    // All docstrings would be for public stuff, so it's safe to start it here
    publicDecls.add("")
    publicDecls.add("/**")

    doc.summary.foreach((docStr) => publicDecls.add(" * " + docStr))

    doc.ref match {
      case TextRef(text) =>
        publicDecls.add(" * " + s"\\sa $text")
      case UrlRef(url, text) =>
        publicDecls.add(" * " + s"\\sa $text")
      case NoRef =>
        // nothing to output
    }

    publicDecls.add(" */")
  }

  override def attrDestructor(attr: AttrLikeSpec, id: Identifier): Unit = {
    val checkLazy = if (attr.isLazy) {
      Some(calculatedFlagForName(id))
    } else {
      None
    }

    val checkNull = if (attr.isNullableSwitchRaw) {
      Some(s"not ${nullFlagForName(id)}")
    } else {
      None
    }

    val checks: List[String] = List(checkLazy, checkNull).flatten

    if (checks.nonEmpty) {
      outSrc.puts(s"if (${checks.mkString(" and ")}) {")
      outSrc.inc
    }

    val (innerType, hasRaw) = attr.dataType match {
      case ut: UserTypeFromBytes => (ut, true)
      case st: SwitchType => (st.combinedType, st.hasSize)
      case t => (t, false)
    }

    destructMember(id, innerType, attr.isArray, hasRaw)

    if (checks.nonEmpty) {
      outSrc.dec
      outSrc.puts("}")
    }
  }

  def destructMember(id: Identifier, innerType: DataType, isArray: Boolean, hasRaw: Boolean): Unit = {
    if (isArray) {
      // raw is std::vector<string>*, no need to delete its contents, but we
      // need to clean up the vector pointer itself
      if (hasRaw) {
        outSrc.puts(s"delete ${privateMemberName(RawIdentifier(id))};")

        // IO is std::vector<kstream*>*, needs destruction of both members
        // and the vector pointer itself
        val ioVar = privateMemberName(IoStorageIdentifier(RawIdentifier(id)))
        destructVector(s"$kstreamName*", ioVar)
        outSrc.puts(s"delete $ioVar;")
      }

      // main member contents
      if (needsDestruction(innerType)) {
        val arrVar = privateMemberName(id)

        // C++ specific substitution: AnyType results from generic struct + raw bytes
        // so we would assume that only generic struct needs to be cleaned up
        val realType = innerType match {
          case AnyType => KaitaiStructType
          case _ => innerType
        }

        destructVector(kaitaiType2NativeType(realType), arrVar)
      }

      // main member is a std::vector of something, always needs destruction
      outSrc.puts(s"delete ${privateMemberName(id)};")
    } else {

      if (hasRaw) {
        // raw is just a string, no need to cleanup
        // IO is kstream*, needs destruction
        outSrc.puts(s"delete ${privateMemberName(IoStorageIdentifier(RawIdentifier(id)))};")
      }

      if (needsDestruction(innerType))
        outSrc.puts(s"delete ${privateMemberName(id)};")
    }
  }

  def needsDestruction(t: DataType): Boolean = t match {
    case _: UserType | _: ArrayType | KaitaiStructType | AnyType => true
    case _ => false
  }

  /**
    * Generates std::vector contents destruction loop.
    * @param elType element type, i.e. XXX in `std::vector&lt;XXX&gt;`
    * @param arrVar variable name that holds pointer to std::vector
    */
  def destructVector(elType: String, arrVar: String): Unit = {
    outSrc.puts(s"for (std::vector<$elType>::iterator it = $arrVar->begin(); it != $arrVar->end(); ++it) {")
    outSrc.inc
    outSrc.puts("delete *it;")
    outSrc.dec
    outSrc.puts("}")
  }

  override def attrParseHybrid(leProc: () => Unit, beProc: () => Unit): Unit = {
    outSrc.puts("if (m__is_le == 1) {")
    outSrc.inc
    leProc()
    outSrc.dec
    outSrc.puts("} else {")
    outSrc.inc
    beProc()
    outSrc.dec
    outSrc.puts("}")
  }

  override def attrFixedContentsParse(attrName: Identifier, contents: String): Unit =
    outSrc.puts(s"${privateMemberName(attrName)} = $normalIO->ensure_fixed_contents($contents);")

  override def attrProcess(proc: ProcessExpr, varSrc: Identifier, varDest: Identifier): Unit = {
    val srcName = privateMemberName(varSrc)
    val destName = privateMemberName(varDest)

    proc match {
      case ProcessXor(xorValue) =>
        val procName = translator.detectType(xorValue) match {
          case _: IntType => "process_xor_one"
          case _: BytesType => "process_xor_many"
        }
        outSrc.puts(s"$destName = $kstreamName::$procName($srcName, ${expression(xorValue)});")
      case ProcessZlib =>
        outSrc.puts(s"$destName = $kstreamName::process_zlib($srcName);")
      case ProcessRotate(isLeft, rotValue) =>
        val expr = if (isLeft) {
          expression(rotValue)
        } else {
          s"8 - (${expression(rotValue)})"
        }
        outSrc.puts(s"$destName = $kstreamName::process_rotate_left($srcName, $expr);")
      case ProcessCustom(name, args) =>
        val procClass = name.map((x) => type2class(x)).mkString("::")
        val procName = s"_process_${idToStr(varSrc)}"

        importListSrc.add(name.last + ".h")

        outSrc.puts(s"$procClass $procName(${args.map(expression).mkString(", ")});")
        outSrc.puts(s"$destName = $procName.decode($srcName);")
    }
  }

  override def allocateIO(id: Identifier, rep: RepeatSpec, extraAttrs: ListBuffer[AttrSpec]): String = {
    val memberName = privateMemberName(id)
    val ioId = IoStorageIdentifier(id)

    val args = rep match {
      case RepeatEos | RepeatExpr(_) => s"$memberName->at($memberName->size() - 1)"
      case RepeatUntil(_) => translator.doName(Identifier.ITERATOR2)
      case NoRepeat => memberName
    }

    val newStream = s"new $kstreamName($args)"

    val (ioType, ioName) = rep match {
      case NoRepeat =>
        outSrc.puts(s"${privateMemberName(ioId)} = $newStream;")
        (KaitaiStreamType, privateMemberName(ioId))
      case _ =>
        val localIO = s"io_${idToStr(id)}"
        outSrc.puts(s"$kstreamName* $localIO = $newStream;")
        outSrc.puts(s"${privateMemberName(ioId)}->push_back($localIO);")
        (ArrayType(KaitaiStreamType), localIO)
    }

    Utils.addUniqueAttr(extraAttrs, AttrSpec(List(), ioId, ioType))
    ioName
  }

  override def useIO(ioEx: Ast.expr): String = {
    outSrc.puts(s"$kstreamName *io = ${expression(ioEx)};")
    "io"
  }

  override def pushPos(io: String): Unit =
    outSrc.puts(s"std::streampos _pos = $io->pos();")

  override def seek(io: String, pos: Ast.expr): Unit =
    outSrc.puts(s"$io->seek(${expression(pos)});")

  override def popPos(io: String): Unit =
    outSrc.puts(s"$io->seek(_pos);")

  override def alignToByte(io: String): Unit =
    outSrc.puts(s"$io->align_to_byte();")

  override def instanceClear(instName: InstanceIdentifier): Unit =
    outSrc.puts(s"${calculatedFlagForName(instName)} = false;")

  override def instanceSetCalculated(instName: InstanceIdentifier): Unit =
    outSrc.puts(s"${calculatedFlagForName(instName)} = true;")

  override def condIfSetNull(instName: Identifier): Unit =
    outSrc.puts(s"${nullFlagForName(instName)} = true;")

  override def condIfSetNonNull(instName: Identifier): Unit =
    outSrc.puts(s"${nullFlagForName(instName)} = false;")

  override def condIfHeader(expr: Ast.expr): Unit = {
    outSrc.puts(s"if (${expression(expr)}) {")
    outSrc.inc
  }

  override def condIfFooter(expr: Ast.expr): Unit = {
    outSrc.dec
    outSrc.puts("}")
  }

  override def condRepeatEosHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean): Unit = {
    importListHdr.add("vector")

    if (needRaw) {
      outSrc.puts(s"${privateMemberName(RawIdentifier(id))} = new std::vector<std::string>();")
      outSrc.puts(s"${privateMemberName(IoStorageIdentifier(RawIdentifier(id)))} = new std::vector<$kstreamName*>();")
    }
    outSrc.puts(s"${privateMemberName(id)} = new std::vector<${kaitaiType2NativeType(dataType)}>();")
    outSrc.puts("{")
    outSrc.inc
    outSrc.puts("int i = 0;")
    outSrc.puts(s"while (not $io->is_eof()) {")
    outSrc.inc
  }

  override def handleAssignmentRepeatEos(id: Identifier, expr: String): Unit = {
    outSrc.puts(s"${privateMemberName(id)}->push_back($expr);")
  }

  override def condRepeatEosFooter: Unit = {
    outSrc.puts("i++;")
    outSrc.dec
    outSrc.puts("}")
    outSrc.dec
    outSrc.puts("}")
  }

  override def condRepeatExprHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, repeatExpr: Ast.expr): Unit = {
    importListHdr.add("vector")

    val lenVar = s"l_${idToStr(id)}"
    outSrc.puts(s"int $lenVar = ${expression(repeatExpr)};")
    if (needRaw) {
      val rawId = privateMemberName(RawIdentifier(id))
      outSrc.puts(s"$rawId = new std::vector<std::string>();")
      outSrc.puts(s"$rawId->reserve($lenVar);")
      val ioId = privateMemberName(IoStorageIdentifier(RawIdentifier(id)))
      outSrc.puts(s"$ioId = new std::vector<$kstreamName*>();")
      outSrc.puts(s"$ioId->reserve($lenVar);")
    }
    outSrc.puts(s"${privateMemberName(id)} = new std::vector<${kaitaiType2NativeType(dataType)}>();")
    outSrc.puts(s"${privateMemberName(id)}->reserve($lenVar);")
    outSrc.puts(s"for (int i = 0; i < $lenVar; i++) {")
    outSrc.inc
  }

  override def handleAssignmentRepeatExpr(id: Identifier, expr: String): Unit = {
    outSrc.puts(s"${privateMemberName(id)}->push_back($expr);")
  }

  override def condRepeatExprFooter: Unit = {
    outSrc.dec
    outSrc.puts("}")
  }

  override def condRepeatUntilHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, untilExpr: expr): Unit = {
    importListHdr.add("vector")

    if (needRaw) {
      outSrc.puts(s"${privateMemberName(RawIdentifier(id))} = new std::vector<std::string>();")
      outSrc.puts(s"${privateMemberName(IoStorageIdentifier(RawIdentifier(id)))} = new std::vector<$kstreamName*>();")
    }
    outSrc.puts(s"${privateMemberName(id)} = new std::vector<${kaitaiType2NativeType(dataType)}>();")
    outSrc.puts("{")
    outSrc.inc
    outSrc.puts("int i = 0;")
    outSrc.puts(s"${kaitaiType2NativeType(dataType)} ${translator.doName("_")};")
    outSrc.puts("do {")
    outSrc.inc
  }

  override def handleAssignmentRepeatUntil(id: Identifier, expr: String, isRaw: Boolean): Unit = {
    val (typeDecl, tempVar) = if (isRaw) {
      ("std::string ", translator.doName(Identifier.ITERATOR2))
    } else {
      ("", translator.doName(Identifier.ITERATOR))
    }
    outSrc.puts(s"$typeDecl$tempVar = $expr;")
    outSrc.puts(s"${privateMemberName(id)}->push_back($tempVar);")
  }

  override def condRepeatUntilFooter(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, untilExpr: expr): Unit = {
    typeProvider._currentIteratorType = Some(dataType)
    outSrc.puts("i++;")
    outSrc.dec
    outSrc.puts(s"} while (not (${expression(untilExpr)}));")
    outSrc.dec
    outSrc.puts("}")
  }

  override def handleAssignmentSimple(id: Identifier, expr: String): Unit = {
    outSrc.puts(s"${privateMemberName(id)} = $expr;")
  }

  override def parseExpr(dataType: DataType, assignType: DataType, io: String, defEndian: Option[FixedEndian]): String = {
    dataType match {
      case t: ReadableType =>
        s"$io->read_${t.apiCall(defEndian)}()"
      case blt: BytesLimitType =>
        s"$io->read_bytes(${expression(blt.size)})"
      case _: BytesEosType =>
        s"$io->read_bytes_full()"
      case BytesTerminatedType(terminator, include, consume, eosError, _) =>
        s"$io->read_bytes_term($terminator, $include, $consume, $eosError)"
      case BitsType1 =>
        s"$io->read_bits_int(1)"
      case BitsType(width: Int) =>
        s"$io->read_bits_int($width)"
      case t: UserType =>
        val addParams = Utils.join(t.args.map((a) => translator.translate(a)), "", ", ", ", ")
        val addArgs = if (t.isOpaque) {
          ""
        } else {
          val parent = t.forcedParent match {
            case Some(USER_TYPE_NO_PARENT) => "0"
            case Some(fp) => translator.translate(fp)
            case None => "this"
          }
          val addEndian = t.classSpec.get.meta.endian match {
            case Some(InheritedEndian) => ", m__is_le"
            case _ => ""
          }
          s", $parent, ${privateMemberName(RootIdentifier)}$addEndian"
        }
        s"new ${types2class(t.name)}($addParams$io$addArgs)"
    }
  }

  override def bytesPadTermExpr(expr0: String, padRight: Option[Int], terminator: Option[Int], include: Boolean) = {
    val expr1 = padRight match {
      case Some(padByte) => s"$kstreamName::bytes_strip_right($expr0, $padByte)"
      case None => expr0
    }
    val expr2 = terminator match {
      case Some(term) => s"$kstreamName::bytes_terminate($expr1, $term, $include)"
      case None => expr1
    }
    expr2
  }

  /**
    * Designates switch mode. If false, we're doing real switch-case for this
    * attribute. If true, we're doing if-based emulation.
    */
  var switchIfs = false

  override def switchStart(id: Identifier, on: Ast.expr): Unit = {
    val onType = translator.detectType(on)

    // Determine switching mode for this construct based on type
    switchIfs = onType match {
      case _: IntType | _: EnumType => false
      case _ => true
    }

    if (switchIfs) {
      outSrc.puts("{")
      outSrc.inc
      outSrc.puts(s"${kaitaiType2NativeType(onType)} on = ${expression(on)};")
    } else {
      outSrc.puts(s"switch (${expression(on)}) {")
      outSrc.inc
    }
  }

  override def switchCaseFirstStart(condition: Ast.expr): Unit = {
    if (switchIfs) {
      outSrc.puts(s"if (on == ${expression(condition)}) {")
      outSrc.inc
    } else {
      outSrc.puts(s"case ${expression(condition)}: {")
      outSrc.inc
    }
  }

  override def switchCaseStart(condition: Ast.expr): Unit = {
    if (switchIfs) {
      outSrc.puts(s"else if (on == ${expression(condition)}) {")
      outSrc.inc
    } else {
      outSrc.puts(s"case ${expression(condition)}: {")
      outSrc.inc
    }
  }

  override def switchCaseEnd(): Unit = {
    if (switchIfs) {
      outSrc.dec
      outSrc.puts("}")
    } else {
      outSrc.puts("break;")
      outSrc.dec
      outSrc.puts("}")
    }
  }

  override def switchElseStart(): Unit = {
    if (switchIfs) {
      outSrc.puts("else {")
      outSrc.inc
    } else {
      outSrc.puts("default: {")
      outSrc.inc
    }
  }

  override def switchEnd(): Unit =
    if (switchIfs) {
      outSrc.dec
      outSrc.puts("}")
    } else {
      outSrc.dec
      outSrc.puts("}")
    }

  override def switchBytesOnlyAsRaw = true

  override def instanceDeclaration(attrName: InstanceIdentifier, attrType: DataType, isNullable: Boolean): Unit = {
    privateDecls.addUnique(s"bool ${calculatedFlagForName(attrName)};")
    privateDecls.addUnique(s"${kaitaiType2NativeType(attrType)} ${privateMemberName(attrName)};")
    declareNullFlag(attrName, isNullable)
  }

  override def instanceHeader(className: List[String], instName: InstanceIdentifier, dataType: DataType, isNullable: Boolean): Unit = {
    publicDecls.addUnique(s"${kaitaiType2NativeType(dataType)} ${publicMemberName(instName)}();")

    outSrc.puts
    outSrc.puts(s"${kaitaiType2NativeType(dataType, true)} ${types2class(className)}::${publicMemberName(instName)}() {")
    outSrc.inc
  }

  override def instanceFooter: Unit = {
    outSrc.dec
    outSrc.puts("}")
  }

  override def instanceCheckCacheAndReturn(instName: InstanceIdentifier): Unit = {
    outSrc.puts(s"if (${calculatedFlagForName(instName)})")
    outSrc.inc
    instanceReturn(instName)
    outSrc.dec
  }

  override def instanceReturn(instName: InstanceIdentifier): Unit = {
    outSrc.puts(s"return ${privateMemberName(instName)};")
  }

  override def enumDeclaration(curClass: List[String], enumName: String, enumColl: Seq[(Long, EnumValueSpec)]): Unit = {
    val enumClass = types2class(List(enumName))

    outHdr.puts
    outHdr.puts(s"enum $enumClass {")
    outHdr.inc

    if (enumColl.size > 1) {
      enumColl.dropRight(1).foreach { case (id, label) =>
        outHdr.puts(s"${value2Const(enumName, label.name)} = $id,")
      }
    }
    enumColl.last match {
      case (id, label) =>
        outHdr.puts(s"${value2Const(enumName, label.name)} = $id")
    }

    outHdr.dec
    outHdr.puts("};")
  }

  def value2Const(enumName: String, label: String) = (enumName + "_" + label).toUpperCase

  def kaitaiType2NativeType(attrType: DataType, absolute: Boolean = false): String = {
    attrType match {
      case Int1Type(false) => "uint8_t"
      case IntMultiType(false, Width2, _) => "uint16_t"
      case IntMultiType(false, Width4, _) => "uint32_t"
      case IntMultiType(false, Width8, _) => "uint64_t"

      case Int1Type(true) => "int8_t"
      case IntMultiType(true, Width2, _) => "int16_t"
      case IntMultiType(true, Width4, _) => "int32_t"
      case IntMultiType(true, Width8, _) => "int64_t"

      case FloatMultiType(Width4, _) => "float"
      case FloatMultiType(Width8, _) => "double"

      case BitsType(_) => "uint64_t"

      case _: BooleanType => "bool"
      case CalcIntType => "int32_t"
      case CalcFloatType => "double"

      case _: StrType => "std::string"
      case _: BytesType => "std::string"

      case t: UserType =>
        val typeStr = types2class(if (absolute) {
          t.classSpec.get.name
        } else {
          t.name
        })
        s"$typeStr*"

      case t: EnumType =>
        types2class(if (absolute) {
          t.enumSpec.get.name
        } else {
          t.name
        })

      case ArrayType(inType) => s"std::vector<${kaitaiType2NativeType(inType, absolute)}>*"

      case KaitaiStreamType => s"$kstreamName*"
      case KaitaiStructType => s"$kstructName*"

      case SwitchType(on, cases) =>
        kaitaiType2NativeType(TypeDetector.combineTypes(
          // C++ does not have a concept of AnyType, and common use case "lots of incompatible UserTypes
          // for cases + 1 BytesType for else" combined would result in exactly AnyType - so we try extra
          // hard to avoid that here with this pre-filtering. In C++, "else" case with raw byte array would
          // be available through _raw_* attribute anyway.
          cases.filterNot { case (caseExpr, caseValue) => caseExpr == SwitchType.ELSE_CONST }.values
        ), absolute)
    }
  }

  def defineName(className: String) = className.toUpperCase + "_H_"

  /**
    * Returns name of a member that stores "calculated flag" for a given lazy
    * attribute. That is, if it's true, then calculation have already taken
    * place and we need to return already calculated member in a getter, or,
    * if it's false, we need to calculate / parse it first.
    * @param ksName attribute ID
    * @return calculated flag member name associated with it
    */
  def calculatedFlagForName(ksName: Identifier) =
    s"f_${idToStr(ksName)}"

  /**
    * Returns name of a member that stores "null flag" for a given attribute,
    * that is, if it's true, then associated attribute is null.
    * @param ksName attribute ID
    * @return null flag member name associated with it
    */
  def nullFlagForName(ksName: Identifier) =
    s"n_${idToStr(ksName)}"

  override def idToStr(id: Identifier): String = {
    id match {
      case RawIdentifier(inner) => s"_raw_${idToStr(inner)}"
      case IoStorageIdentifier(inner) => s"_io_${idToStr(inner)}"
      case si: SpecialIdentifier => si.name
      case ni: NamedIdentifier => ni.name
      case NumberedIdentifier(idx) => s"_${NumberedIdentifier.TEMPLATE}$idx"
      case ni: InstanceIdentifier => ni.name
    }
  }

  override def privateMemberName(id: Identifier): String = s"m_${idToStr(id)}"

  override def publicMemberName(id: Identifier): String = idToStr(id)

  override def localTemporaryName(id: Identifier): String = s"_t_${idToStr(id)}"

  override def paramName(id: Identifier): String = s"p_${idToStr(id)}"

  def declareNullFlag(attrName: Identifier, isNullable: Boolean) = {
    if (isNullable) {
      privateDecls.addUnique(s"bool ${nullFlagForName(attrName)};")
      publicDecls.addUnique(s"bool _is_null_${idToStr(attrName)}() { ${publicMemberName(attrName)}(); return ${nullFlagForName(attrName)}; };")
    }
  }

  def type2class(name: String) = name + "_t"
}

object CppCompiler extends LanguageCompilerStatic with StreamStructNames {
  override def getCompiler(
    tp: ClassTypeProvider,
    config: RuntimeConfig
  ): LanguageCompiler = new CppCompiler(tp, config)

  override def kstructName = "kaitai::kstruct"
  override def kstreamName = "kaitai::kstream"

  def types2class(components: List[String]) = {
    components.map {
      case "kaitai_struct" => "kaitai::kstruct"
      case s => s + "_t"
    }.mkString("::")
  }
}
