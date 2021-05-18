package util

import org.apache.commons.lang3.StringUtils

import scala.io.Source

object Template {
  val baseFile: String = "base.html"

  def htmlFile(filename: String):String = Source.fromResource(filename).getLines.mkString("\n")

  def baseHtml(contentFile: String, title: String): String = htmlFile(baseFile)
    .replace("{{ content }}", htmlFile(contentFile))
    .replace("{{ title }}", title)

  def putData(template: String, data: Map[String, String]): String =
    data.foldLeft(template) { case (acc, (key, value)) =>
      acc.replace(s"{{ $key }}", value)
    }

  def putFor(template: String, label: String, seqData: Seq[Map[String, String]]): String = {
    Option(StringUtils.substringBetween(template, s"{{ for $label }}", "{{ end for }}"))
      .map { pulled =>
        (pulled, seqData.foldLeft("") { (acc, data) => acc ++ putData(pulled, data) })
      } match {
      case Some((pulled, body)) => template.replace(s"{{ for $label }}$pulled{{ end for }}", body)
      case None => template
    }
  }

  def putIf(template: String, label: String, data: Map[String, String], condition: Boolean): String =
    Option(StringUtils.substringBetween(template, s"{{ if $label }}", "{{ end if }}"))
      .map {
        pulled => (pulled, putData(pulled, data))
      } match {
      case Some((pulled, body)) if condition => template.replace(s"{{ if $label }}$pulled{{ end if }}", body)
      case Some((pulled, _)) if !condition => template.replace(s"{{ if $label }}$pulled{{ end if }}", "")
      case None => template
    }
}
