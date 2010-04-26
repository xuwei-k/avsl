/*
  ---------------------------------------------------------------------------
  This software is released under a BSD license, adapted from
  http://opensource.org/licenses/bsd-license.php

  Copyright (c) 2010, Brian M. Clapper
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met:

  * Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

  * Neither the names "clapper.org", "AVSL", nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
  IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  ---------------------------------------------------------------------------
*/

/**
 * AVSL logging classes.
 */
package org.clapper.avsl.config

import org.clapper.avsl._
import org.clapper.avsl.formatter._
import org.clapper.avsl.handler._

import grizzled.config.{Configuration, Section}

import scala.annotation.tailrec
import scala.io.Source
import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}

import java.net.{MalformedURLException, URL}
import java.io.File

/*---------------------------------------------------------------------------*\
                                  Classes
\*---------------------------------------------------------------------------*/

/**
 * Arguments for a formatter or handler.
 */
class ConfiguredArguments(argMap: Map[String, String])
{
    def apply(name: String) = argMap(name)
    def get(name: String): Option[String] = argMap.get(name)
    def getOrElse(name: String, default: String) =
        argMap.getOrElse(name, default)
}

/**
 * Convenience object that contains no arguments.
 */
object NoConfiguredArguments
extends ConfiguredArguments(Map.empty[String, String])

/**
 * The configuration handler.
 */
class AVSLConfiguration(source: Source) extends Configuration
{
    def this(url: URL) = this(Source.fromURL(url))

    load(source)

    val loggerTree = getLoggers
    val handlers = getHandlers
    val formatters = getFormatters

    validate

    def loggerConfigFor(name: String): LoggerConfig =
    {
        val rootNode = loggerTree.rootNode

        def find(namePieces: List[String], current: LoggerConfigNode):
            LoggerConfigNode =
        {
            namePieces match
            {
                case namePiece :: Nil =>
                    current.children.get(namePiece) match
                    {
                        case None       => current // not configured
                        case Some(node) => node
                    }

                case namePiece :: tail =>
                    current.children.get(namePiece) match
                    {
                        case None       => current // not configured
                        case Some(node) => find(tail, node)
                    }

                case Nil =>
                    rootNode
            }
        }

        val node = name match
        {
            case Logger.RootLoggerName =>
                rootNode
            case _ =>
                find(name.split("""\.""").toList, rootNode)
        }

        node.config match
        {
            case None         => rootNode.config.get
            case Some(config) => config
        }
    }

    /**
     * Validate the loggers, handlers and formatters.
     */
    private def validate =
    {
        // Individual validators return an error message (Some(msg)) or None.
        val errorMessage = validateLoggers.getOrElse("") +
                           validateFormatters.getOrElse("") +
                           validateHandlers.getOrElse("")
        if (errorMessage != "")
            throw new AVSLConfigException(errorMessage)
    }

    private def stringToOption(s: String): Option[String] =
        if (s == "") None else Some(s)

    /**
     * Extract the logger configuration sections, map them into a tree (by
     * splitting dot-separated class names into individual name nodes), and
     * return the result. The root logger will be at the top of the tree,
     * whether configured or not.
     *
     * @return the logger configuration tree
     */
    private def getLoggers: LoggerConfigTree =
    {
        val re = ("^" + AVSLConfiguration.LoggerPrefix).r
        val configs = Map.empty[String, LoggerConfig] ++
                      matchingSections(re).map(new LoggerConfig(this, _)).
                      map(cfg => (cfg.name, cfg))

        def makeRoot =
        {
            val sectionName = AVSLConfiguration.LoggerPrefix +
                              Logger.RootLoggerName
            val args = Map("level" -> "error")

            new LoggerConfig(this, new Section(sectionName, args))
        }

        val root = configs.getOrElse(Logger.RootLoggerName, makeRoot)
        val topNode = makeTree(root, configs.values)
        new LoggerConfigTree(topNode)
    }

    /**
     * Map the logger configuration items into a tree.
     *
     * @param root    the root logger configuration item
     * @param configs all the logger configuration items from the config
     *
     * @return the top-level (root) logger configuration node
     */
    private def makeTree(root: LoggerConfig, 
                         configs: Iterable[LoggerConfig]): LoggerConfigNode =
    {
        def noChildren = MutableMap.empty[String, LoggerConfigNode]

        val rootNode = LoggerConfigNode(root.pattern, Some(root), noChildren)

        /**
         * Create a node for a logger configuration item and insert it
         * into the tree
         */
        @tailrec def insert(cursor: LoggerConfigNode,
                            config: LoggerConfig,
                            patternParts: List[String]): LoggerConfigNode =
        {
            patternParts match
            {
                case leaf :: Nil =>
                    val node = cursor.children.get(leaf) match
                    {
                        case Some(node) if (node.config != None) =>
                            throw new AVSLConfigException(
                                "Multiple loggers for " +
                                node.config.get.pattern
                            )

                        case Some(node) =>
                            // Previously filled-in stub node.
                            LoggerConfigNode(leaf, Some(config), node.children)

                        case None =>
                            LoggerConfigNode(leaf, Some(config), noChildren)
                    }
                                                      
                    cursor.children += (leaf -> node)
                    node

                case mid :: tail =>
                    val node = cursor.children.get(mid) match
                    {
                        case Some(node) =>
                            node
                        case None =>
                            LoggerConfigNode(mid, None, noChildren)
                    }
                    cursor.children += (mid -> node)
                    insert(node, config, tail)

                case Nil =>
                    cursor
            }
        }

        for (config <- configs; if (config.name != root.name))
            insert(rootNode, config, config.pattern.split("""\.""").toList)

        rootNode
    }

    /**
     * Validate the loggers.
     */
    private def validateLoggers: Option[String] =
    {
        def checkHandlers(logger: LoggerConfig,
                          handlersToCheck: List[String]): List[String] =
        {
            def checkMany(handlersToCheck: List[String]): List[Option[String]] =
            {
                handlersToCheck match
                {
                    case Nil =>
                        Nil

                    case handler :: Nil =>
                        List(checkOne(handler))

                    case handler :: tail =>
                        checkOne(handler) :: checkMany(tail)
                }
            }

            def checkOne(handler: String): Option[String] =
            {
                if (this.handlers.contains(handler))
                    None
                else
                    Some("Logger \"" + logger.name + "\" refers to " +
                         "unknown handler \"" + handler + "\"")
            }
                
            // Map from list of Option[String] values to strings, filtering
            // out the None elements.
            val handlerNames = logger.handlerNames.filter(_ != "")
            checkMany(handlerNames).filter(_ != None).map(_.get)
        }

        def checkNode(node: LoggerConfigNode): List[String] =
        {
            val errors =
                node.config match
                {
                    case None =>
                        Nil

                    case Some(config) =>
                        checkHandlers(config, config.handlerNames)
                }

            errors ::: checkNodes(node.children.values.toList)
        }

        def checkNodes(nodes: List[LoggerConfigNode]): List[String] =
        {
            nodes match
            {
                case node :: Nil  => checkNode(node)
                case node :: tail => checkNode(node) ++ checkNodes(tail)
                case Nil          => Nil
            }
        }

        stringToOption(checkNode(loggerTree.rootNode) mkString "\n")
    }

    /**
     * Get the formatters.
     */
    private def getFormatters: Map[String, FormatterConfig] =
    {
        val re = ("^" + AVSLConfiguration.FormatterPrefix).r
        val configs = matchingSections(re).map(new FormatterConfig(this, _))
        Map.empty[String, FormatterConfig] ++ configs.map(c => (c.name, c))
    }

    /**
     * Validate the formatters.
     */
    private def validateFormatters: Option[String] = None

    /**
     * Get the handlers.
     */
    private def getHandlers: Map[String, HandlerConfig] =
    {
        val re = ("^" + AVSLConfiguration.HandlerPrefix).r
        val configs = matchingSections(re).map(new HandlerConfig(this, _))
        Map.empty[String, HandlerConfig] ++ configs.map(cfg => (cfg.name, cfg))
    }

    /**
     * Validate the handlers.
     */
    private def validateHandlers: Option[String] =
    {
        def doValidation: String =
        {
            def badFormatter(name: String) = ! this.formatters.contains(name)
            def badFormatterMessage(handler: HandlerConfig) = 
                "Handler \"" + handler.name + "\" refers to unknown " +
                "formatter \"" + handler.formatterName + "\""

            handlers.values.filter(h => badFormatter(h.formatterName)).
                map(h => Some(badFormatterMessage(h))).map(_.get).mkString("\n")
        }

        doValidation match
        {
            case "" => None
            case s  => Some(s)
        }
    }
}

/**
 * Common configuration methods used by all configuration sections.
 */
private[avsl] trait ConfigurationItem
{
    val config: AVSLConfiguration
    val section: Section

    protected def requiredString(option: String): String =
    {
        section.options.get(option) match
        {
            case Some(value) =>
                value
            case None =>
                throw new AVSLMissingRequiredOptionException(section.name,
                                                             option)
        }
    }

    protected def configuredLevel: LogLevel =
    {
        section.options.get(AVSLConfiguration.LevelKeyword) match
        {
            case Some(value) =>
                LogLevel.fromString(value) match
                {
                    case Some(level) =>
                        level
                    case None =>
                        throw new AVSLConfigSectionException(
                            section.name, "Bad log level: \"" + value + "\""
                        )
                }

            case None =>
                throw new AVSLMissingRequiredOptionException(
                    section.name, AVSLConfiguration.LevelKeyword
                )
        }
    }

    protected def classOption(keyword: String,
                              aliases: Map[String,Class[_]]): Option[Class[_]] =
    {
        Util.lookupClass(section.options.get(keyword), aliases)
    }

    protected def getArgs(filterOp: String => Boolean): ConfiguredArguments =
    {
        val argMap =
            Map.empty[String, String] ++
            section.options.keys.filter(filterOp).
                    map(k => (k, section.options(k)))
        new ConfiguredArguments(argMap)
    }
}

/**
 * Information about a configured logger. These items are arranged in a
 * hierarchy, by name (which is usually a class name), with the root logger
 * at the top.
 */
private[avsl] class LoggerConfig(val config: AVSLConfiguration,
                                 val section: Section)
extends ConfigurationItem
{
    val name = section.name.replace(AVSLConfiguration.LoggerPrefix, "")
    val pattern = if (name == "root") "" else requiredString("pattern")
    val level = configuredLevel
    val handlerNames = section.options.getOrElse("handlers", "").
                               split("""[\s,]+""").toList

    if (name == "")
        throw new AVSLConfigSectionException(section.name,
                                             "Bad logger section name: \"" +
                                             section.name + "\"")

    override def toString = name
}

/**
 * The logger tree.
 */
private[avsl] class LoggerConfigTree(val rootNode: LoggerConfigNode)
{
    import java.io.{OutputStreamWriter, PrintStream, PrintWriter, Writer}

    def printTree(stream: PrintStream): Unit =
        printTree(new OutputStreamWriter(stream))

    def printTree(writer: Writer): Unit =
    {
        val out = new PrintWriter(writer)

        def printSubtree(node: LoggerConfigNode, indentation: Int = 0): Unit =
        {
            def indent = "  " * indentation

            def handlerNames = node.config match
            {
                case None    => ""
                case Some(l) => l.handlerNames.mkString(", ")
            }

            def level = node.config match
            {
                case None    => "<root-level>"
                case Some(l) => l.level.toString
            }

            val label = if (node.name == "") "ROOT" else node.name
            out.println(indent + label + ": children=" +
                        node.children.values.map(_.name).mkString(", ") +
                        ", handlers=" + handlerNames + ", level=" + level)
            for (c <- node.children.values)
                printSubtree(c, indentation + 1)
        }

        printSubtree(rootNode)
        out.flush
    }
}

/**
 * A node in the logger tree.
 */
private[avsl]
case class LoggerConfigNode(val name: String,
                            val config: Option[LoggerConfig],
                            val children: MutableMap[String, LoggerConfigNode])
{
    override def toString = if (name == "") "<root>" else name
}

/**
 * Information about a configured handler.
 */
private[avsl] class HandlerConfig(val config: AVSLConfiguration,
                                  val section: Section)
extends ConfigurationItem
{
    val ClassAliases = Map("DefaultHandler" -> classOf[ConsoleHandler],
                           "ConsoleHandler" -> classOf[ConsoleHandler],
                           "FileHandler"    -> classOf[FileHandler],
                           "NullHandler"    -> classOf[NullHandler])
    val DefaultHandlerClass = classOf[ConsoleHandler]

    val name = section.name.replace(AVSLConfiguration.HandlerPrefix, "")
    val level = configuredLevel
    val args = getArgs(! isReserved(_))
    val formatterName = requiredString("formatter")
    val handlerClass =
        classOption("class", ClassAliases).getOrElse(DefaultHandlerClass)

    if (name == "")
        throw new AVSLConfigSectionException(section.name,
                                             "Bad handler section name: \"" +
                                             section.name + "\"")

    private def isReserved(s: String): Boolean =
        (s == "class") ||
        (s == "formatter") ||
        (s == AVSLConfiguration.LevelKeyword) ||
        (s.startsWith(AVSLConfiguration.HandlerPrefix))

}

/**
 * Information about a configured formatter.
 */
private[avsl] class FormatterConfig(val config: AVSLConfiguration,
                                    val section: Section)
extends ConfigurationItem
{
    val name = section.name.replace(AVSLConfiguration.FormatterPrefix, "")
    val args = getArgs(! isReserved(_))

    val formatterClass =
        classOption("class", FormatterConfig.ClassAliases).
        getOrElse(FormatterConfig.DefaultFormatterClass)

    if (name == "")
        throw new AVSLConfigSectionException(section.name,
                                             "Bad handler section name: \"" +
                                             section.name + "\"")

    private def isReserved(s: String): Boolean =
        (s == "class") ||
        (s.startsWith(AVSLConfiguration.FormatterPrefix))
}

private[avsl] object FormatterConfig
{
    val DefaultFormatterName = "DefaultFormatter"
    val DefaultFormatterClass = classOf[SimpleFormatter]
    val ClassAliases = Map(DefaultFormatterName -> classOf[SimpleFormatter],
                           "NullFormatter"      -> classOf[NullFormatter])

    def formatterClassForName(name: String) =
    {
        Util.lookupClass(Some(name), ClassAliases) match
        {
            case None =>
                throw new AVSLConfigException("Unknown formatter: \"" + name +
                                              "\"")
            case Some(cls) =>
                cls
        }
    }
}

/*---------------------------------------------------------------------------*\
                             Companion Object
\*---------------------------------------------------------------------------*/

//private[avsl]
object AVSLConfiguration
{
    val PropertyName    = "org.clapper.avsl.config"
    val EnvVariable     = "AVSL_CONFIG"
    val DefaultName     = "avsl.conf"
    val LevelKeyword    = "level"
    val HandlerPrefix   = "handler_"
    val LoggerPrefix    = "logger_"
    val FormatterPrefix = "formatter_"

    private val SearchPath = List(sysProperty _, 
                                  envVariable _,
                                  resource _)

    def apply(source: Source): AVSLConfiguration = new AVSLConfiguration(source)

    def apply(): Option[AVSLConfiguration] =
    {
        find match
        {
            case None      => None
            case Some(url) => Some(new AVSLConfiguration(url))
        }
    }

    private def find: Option[URL] =
    {
        def search(functions: List[() => Option[URL]]): Option[URL] =
        {
            functions match
            {
                case function :: Nil =>
                    function()

                case function :: tail =>
                    function() match
                    {
                        case None      => search(tail)
                        case Some(url) => Some(url)
                    }

                case Nil =>
                    None
            }
        }

        search(SearchPath)
    }

    private def resource(): Option[URL] =
    {
        this.getClass.getClassLoader.getResource("avsl.conf") match
        {
            case null => None
            case url  => Some(url)
        }
    }

    private def envVariable(): Option[URL] =
        urlString("Environment variable " + EnvVariable, 
                   System.getenv(EnvVariable))

    private def sysProperty(): Option[URL] =
        urlString("-D" + PropertyName, System.getProperty(PropertyName))

    private def urlString(label: String, getValue: => String): Option[URL] =
    {
        val s = getValue
        if ((s == null) || (s.trim.length == 0))
            None
        else
            urlOrFile(label, s)
    }

    private def urlOrFile(label: String, s: String): Option[URL] =
    {
        try
        {
            Some(new URL(s))
        }

        catch
        {
            case _: MalformedURLException =>
                val f = new File(s)
                if (! f.exists)
                {
                    println("Warning: " + label + " specifies nonexistent " +
                            "file \"" + f.getPath + "\"")
                    None
                }
                else
                    Some(f.toURI.toURL)
        }
    }
}

/**
 * Utility methods.
 */
private[config] object Util
{
    def lookupClass(name: Option[String],
                    aliases: Map[String, Class[_]]): Option[Class[_]] =
        name match
        {
            case Some(name) if (aliases.keySet.contains(name)) =>
                Some(aliases(name))

            case Some(name) =>
                try
                {
                    Some(Class.forName(name))
                }
                catch
                {
                    case _: ClassNotFoundException =>
                        throw new AVSLConfigException("Cannot load class " +
                                                      name)
                }

            case None =>
                None
        }
}
