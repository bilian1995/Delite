package ppl.dsl.optiql.ops

import scala.virtualization.lms.common.{Base,BaseFatExp}
import ppl.dsl.optiql._
import ppl.delite.framework.datastructures.{DeliteArray,DeliteArrayBuffer}
import java.io.PrintWriter
import reflect.{RefinedManifest,SourceContext}

trait InputReaderOps extends Base { this: OptiQL =>

  object TableInputReader {
    def apply[T<:Record:Manifest](path: Rep[String], shape: Rep[T], separator: Rep[String] = unit("|")) = optiql_table_input_reader(path, shape, separator)
  }

  def optiql_table_input_reader[T<:Record:Manifest](path: Rep[String], shape: Rep[T], separator: Rep[String]): Rep[Table[T]]

}

trait InputReaderOpsExp extends InputReaderOps with BaseFatExp { this: OptiQLExp with InputReaderImplOps =>

  case class OptiQLTableInputReader[T:Manifest](readBlock: Block[Table[T]]) extends DeliteOpSingleTask(readBlock) {
    val mT = manifest[T]
  }

  //TODO: using SingleTask to encapsulate effects, but needs to be fat to return field syms
  def optiql_table_input_reader[T<:Record:Manifest](path: Rep[String], shape: Rep[T], separator: Rep[String]) = {
    reflectEffect(OptiQLTableInputReader(reifyEffectsHere(optiql_table_input_reader_impl(path,shape,separator))))
  }

  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit ctx: SourceContext): Exp[A] = (e match {
    case e@OptiQLTableInputReader(block) => reflectPure(new { override val original = Some(f,e) } with OptiQLTableInputReader(f(block))(e.mT))(mtype(manifest[A]),implicitly[SourceContext])      
    case Reflect(e@OptiQLTableInputReader(block), u, es) => reflectMirrored(Reflect(new { override val original = Some(f,e) } with OptiQLTableInputReader(f(block))(e.mT), mapOver(f,u), f(es)))(mtype(manifest[A]))
    case _ => super.mirror(e,f)
  }).asInstanceOf[Exp[A]]

}

trait InputReaderImplOps { this: OptiQL =>
  def optiql_table_input_reader_impl[T<:Record:Manifest](path: Rep[String], shape: Rep[T], separator: Rep[String]): Rep[Table[T]]
}

trait InputReaderImplOpsStandard extends InputReaderImplOps { this: OptiQLLift with OptiQLExp =>
  def optiql_table_input_reader_impl[T<:Record:Manifest](path: Rep[String], shape: Rep[T], separator: Rep[String]) = {
    implicit def rv[T:Manifest](v: Var[T]): Exp[T] = readVar(v) //TODO: why isn't readVar implicit working?

    val input = BufferedReader(FileReader(path))
    val table = DeliteArrayBuffer[T]()
    var record = input.readLine()
    var i = 0
    while (record != unit(null)) {
      val fields = record.split("\\\\Q" + separator + "\\\\E")
      addRecord(table, fields, shape)
      record = input.readLine()
      i += 1
      if (i % 1000000 == 0) println("processed " + i/1000000 + " million records")
    }
    input.close()
    Table(darray_buffer_unsafe_result(table).unsafeImmutable, table.length)
  }

  private def addRecord[T<:Record:Manifest](table: Rep[DeliteArrayBuffer[T]], record: Rep[Array[String]], shape: Rep[T]) {
    val rm = manifest[T] match {
      case rm: RefinedManifest[T] => rm
      case m => throw new RuntimeException("No RefinedManifest for type " + m.toString)
    }
    val elems = rm.fields

    val fields = Range(0,elems.length) map { i =>
      val (field, tp) = elems(i)
      tp.toString match {
        case s if s.contains("String") => (field, record(i))
        case "Double" => (field, Double.parseDouble(record(i)))
        //case "Float" => (field, Float.parseFloat(record(i))
        case "Boolean" => (field, record(i) == "true")
        case "Int" => (field, Integer.parseInt(record(i)))
        case "Char" => (field, record(i).charAt(0))
        case "Boolean" => (field, record(i)=="true")
        case d if d.contains("Date") => (field, Date(record(i)))
        case _ => throw new RuntimeException("Unsupported record field type: " + tp.toString)
      }
    }
    
    val res = struct[T](AnonTag(rm), fields)
    table += res
  }

}
