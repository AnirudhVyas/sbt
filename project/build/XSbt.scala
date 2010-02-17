import sbt._

import java.io.File

class XSbt(info: ProjectInfo) extends ParentProject(info)
{
		/* Subproject declarations*/

	val launchInterfaceSub = project(launchPath / "interface", "Launcher Interface", new LaunchInterfaceProject(_))
	val launchSub = project(launchPath, "Launcher", new LaunchProject(_), launchInterfaceSub)

	val interfaceSub = project("interface", "Interface", new InterfaceProject(_))
	val apiSub = baseProject(compilePath / "api", "API", interfaceSub)

	val controlSub = baseProject(utilPath / "control", "Control")
	val collectionSub = baseProject(utilPath / "collection", "Collections")
	val ioSub = project(utilPath / "io", "IO", new IOProject(_), controlSub)
	val classpathSub = baseProject(utilPath / "classpath", "Classpath")

	val ivySub = project("ivy", "Ivy", new IvyProject(_), interfaceSub, launchInterfaceSub)
	val logSub = baseProject(utilPath / "log", "Logging", interfaceSub)
	val datatypeSub = baseProject("util" /"datatype", "Datatype Generator", ioSub)

	val testSub = project("scripted", "Test", new TestProject(_), ioSub)

	val compileInterfaceSub = project(compilePath / "interface", "Compiler Interface", new CompilerInterfaceProject(_), interfaceSub)

	val taskSub = project(tasksPath, "Tasks", new TaskProject(_), controlSub, collectionSub)
	val cacheSub = project(cachePath, "Cache", new CacheProject(_), taskSub, ioSub)
	val trackingSub = baseProject(cachePath / "tracking", "Tracking", cacheSub)
	val compilerSub = project(compilePath, "Compile", new CompileProject(_),
		launchInterfaceSub, interfaceSub, ivySub, ioSub, classpathSub, compileInterfaceSub)
	val stdTaskSub = project(tasksPath / "standard", "Standard Tasks", new StandardTaskProject(_), trackingSub, compilerSub, apiSub)

	val altCompilerSub = baseProject("main", "Alternate Compiler Test", stdTaskSub, logSub)

	val distSub = project("dist", "Distribution", new DistProject(_))

	def baseProject(path: Path, name: String, deps: Project*) = project(path, name, new Base(_), deps : _*)
	
		/* Multi-subproject paths */
	def cachePath = path("cache")
	def tasksPath = path("tasks")
	def launchPath = path("launch")
	def utilPath = path("util")
	def compilePath = path("compile")

	class DistProject(info: ProjectInfo) extends Base(info) with ManagedBase
	{
		lazy val interDependencies = (XSbt.this.dependencies.toList -- List(distSub, launchSub, launchInterfaceSub, interfaceSub, compileInterfaceSub)) flatMap {
			 case b: Base =>  b :: Nil; case _ => Nil
		}
		override def dependencies = interfaceSub :: compileInterfaceSub :: interDependencies
		lazy val dist = increment dependsOn(publishLocal)
		override def artifactID = "xsbt"
	}

	def increment = task {
		val Array(keep, inc) = projectVersion.value.toString.split("_")
		projectVersion() = Version.fromString(keep + "_" + (inc.toInt + 1)).right.get
		log.info("Version is now " + projectVersion.value)
		None
	}

	def compilerInterfaceClasspath = compileInterfaceSub.projectClasspath(Configurations.Test)

	//run in parallel
	override def parallelExecution = true
	override def disableCrossPaths = true
	def jlineRev = "0.9.94"
	def jlineDep = "jline" % "jline" % jlineRev intransitive()

	override def managedStyle = ManagedStyle.Ivy
	val publishTo = Resolver.file("test-repo", new File("/var/dbwww/repo/"))

		/* Subproject configurations*/
	class LaunchProject(info: ProjectInfo) extends Base(info) with TestWithIO with TestDependencies with ProguardLaunch
	{
		val jline = jlineDep
		val ivy = "org.apache.ivy" % "ivy" % "2.0.0"
		def rawJarPath = jarPath
		override def disableCrossPaths = true
		lazy val rawPackage = packageTask(packagePaths +++ launchInterfaceSub.packagePaths, rawJarPath, packageOptions).dependsOn(compile)
		// to test the retrieving and loading of the main sbt, we package and publish the test classes to the local repository
		override def defaultMainArtifact = Artifact(testID)
		override def projectID = ModuleID(organization, testID, "test-" + version)
		override def packageAction = packageTask(packageTestPaths, outputPath / (testID + "-" + projectID.revision +".jar"), packageOptions).dependsOn(rawTestCompile)
		override def deliverProjectDependencies = Nil
		def testID = "launch-test"
		override def testClasspath = super.testClasspath +++ interfaceSub.compileClasspath +++ interfaceSub.mainResourcesPath
		lazy val rawTestCompile = super.testCompileAction dependsOn(interfaceSub.compile)
		override def testCompileAction = publishLocal dependsOn(rawTestCompile, interfaceSub.publishLocal)
	}
	trait TestDependencies extends Project
	{
		val sc = "org.scala-tools.testing" %% "scalacheck" % "1.6" % "test"
		val sp = "org.scala-tools.testing" % "specs" % "1.6.0" % "test"
	}
	class StandardTaskProject(info: ProjectInfo) extends Base(info)
	{
		override def testClasspath = super.testClasspath +++ compilerSub.testClasspath --- compilerInterfaceClasspath
	}

	class IOProject(info: ProjectInfo) extends Base(info) with TestDependencies
	class TaskProject(info: ProjectInfo) extends Base(info) with TestDependencies
	class CacheProject(info: ProjectInfo) extends Base(info)
	{
		// these compilation options are useful for debugging caches and task composition
		//override def compileOptions = super.compileOptions ++ List(Unchecked,ExplainTypes, CompileOption("-Xlog-implicits"))
		val sbinary = "org.scala-tools.sbinary" %% "sbinary" % "0.3"
	}
	class Base(info: ProjectInfo) extends DefaultProject(info) with ManagedBase with Component with Licensed
	{
		override def scratch = true
		override def consoleClasspath = testClasspath
	}
	trait Licensed extends BasicScalaProject
	{
		def notice = path("NOTICE")
		abstract override def mainResources = super.mainResources +++ notice +++ Path.lazyPathFinder( extractLicenses )
		lazy val seeRegex = """\(see (.*?)\)""".r
		def licensePath(str: String): Path = { val path = Path.fromString(XSbt.this.info.projectPath, str); if(path.exists) path else error("Referenced license '" + str + "' not found at " + path) }
		def seePaths(noticeString: String): List[Path] = seeRegex.findAllIn(noticeString).matchData.map(d => licensePath(d.group(1))).toList
		def extractLicenses = if(!notice.exists) Nil else FileUtilities.readString(notice asFile, log).fold(_ => { log.warn("Could not read NOTICE"); Nil} , seePaths _)
	}
	class CompileProject(info: ProjectInfo) extends Base(info) with TestWithLog with TestWithLaunch
	{
		override def testCompileAction = super.testCompileAction dependsOn(compileInterfaceSub.`package`, interfaceSub.`package`)
		override def testClasspath = super.testClasspath +++ compileInterfaceSub.jarPath +++ interfaceSub.jarPath --- compilerInterfaceClasspath --- interfaceSub.mainCompilePath
		override def compileOptions = super.compileOptions ++ Seq(CompileOption("-Xno-varargs-conversion")) //needed for invoking nsc.scala.tools.Main.process(Array[String])
	}
	class IvyProject(info: ProjectInfo) extends Base(info) with TestWithIO with TestWithLog with TestWithLaunch
	{
		val ivy = "org.apache.ivy" % "ivy" % "2.1.0"
	}
	abstract class BaseInterfaceProject(info: ProjectInfo) extends DefaultProject(info) with ManagedBase with TestWithLog with Component with JavaProject
	class InterfaceProject(info: ProjectInfo) extends BaseInterfaceProject(info)
	{
		override def componentID: Option[String] = Some("xsbti")
		override def packageAction = super.packageAction dependsOn generateVersions
		def versionPropertiesPath = mainResourcesPath / "xsbt.version.properties"
		lazy val generateVersions = task {
			import java.util.{Date, TimeZone}
			val formatter = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss")
			formatter.setTimeZone(TimeZone.getTimeZone("GMT"))
			val timestamp = formatter.format(new Date)
			val content = "version=" + version + "\ntimestamp=" + timestamp
			log.info("Writing version information to " + versionPropertiesPath + " :\n" + content)
			FileUtilities.write(versionPropertiesPath.asFile, content, log)
		}

		override def watchPaths = super.watchPaths +++ apiDefinitionPaths --- sources(generatedBasePath)
		override def mainSourceRoots = super.mainSourceRoots +++ (generatedBasePath ##)
		def srcManagedPath = path("src_managed")
		def generatedBasePath = srcManagedPath / "main" / "java"
		/** Files that define the datatypes.*/
		def apiDefinitionPaths: PathFinder = "definition"
		/** Delete up the generated sources*/
		lazy val cleanManagedSrc = cleanTask(srcManagedPath)
		override def cleanAction = super.cleanAction dependsOn(cleanManagedSrc)
		/** Runs the generator compiled by 'compile', putting the classes in src_managed and processing the definitions 'apiDefinitions'. */
		lazy val generateSource = generateSourceAction dependsOn(cleanManagedSrc, datatypeSub.compile)
		def generateSourceTask(immutable: Boolean, pkg: String, apiDefinitions: PathFinder): Task =
		{
			val m = if(immutable) "immutable" else "mutable"
			generateSourceTask(m :: pkg :: generatedBasePath.absolutePath :: apiDefinitions.get.toList.map(_.absolutePath))
		}
		def generateSourceTask(args: List[String]): Task =
			runTask(datatypeSub.getMainClass(true), datatypeSub.runClasspath, args)
		def generateSourceAction =
			//generateSourceTask(false, "xsbti.api", "definition" +++ "type") &&
			generateSourceTask(true, "xsbti.api", "other" +++ "definition" +++ "type")
		/** compiles the generated sources */
		override def compileAction = super.compileAction dependsOn(generateSource)
	}
	class LaunchInterfaceProject(info: ProjectInfo) extends  BaseInterfaceProject(info)
	{
		override def componentID = None
	}
	class TestProject(info: ProjectInfo) extends Base(info)
	{
		val process = "org.scala-tools.sbt" % "process" % "0.1"
	}
	class CompilerInterfaceProject(info: ProjectInfo) extends Base(info) with SourceProject with TestWithIO with TestWithLog
	{
		def xTestClasspath =  projectClasspath(Configurations.Test)
		def cID = "compiler-interface-src"
		override def componentID = Some(cID)
		// necessary because jline is not distributed with 2.8 and we will get a compile error 
		//val jline = jlineDep artifacts(Artifact("jline", Map("e:component" -> cID)))
		override def ivyXML =
			<dependencies>
				<dependency org="jline" name="jline" rev={jlineRev} transitive="false">
					<artifact name="jline" type="jar" e:component={cID}/>
				</dependency>
			</dependencies>
	}
	trait TestWithIO extends TestWith {
		override def testWithTestClasspath = super.testWithTestClasspath ++ Seq(ioSub)
	}
	trait TestWithLaunch extends TestWith {
		override def testWithTestClasspath = super.testWithTestClasspath ++ Seq(launchSub)
	}
	trait TestWithLog extends TestWith {
		override def testWithCompileClasspath = super.testWithCompileClasspath ++ Seq(logSub)
	}
	trait TestWith extends BasicScalaProject
	{
		def testWithCompileClasspath: Seq[BasicScalaProject] = Nil
		def testWithTestClasspath: Seq[BasicScalaProject] = Nil
		override def testCompileAction = super.testCompileAction dependsOn((testWithTestClasspath.map(_.testCompile) ++ testWithCompileClasspath.map(_.compile)) : _*)
		override def testClasspath = (super.testClasspath /: (testWithTestClasspath.map(_.testClasspath) ++  testWithCompileClasspath.map(_.compileClasspath) ))(_ +++ _)
	}
}
trait JavaProject extends BasicScalaProject
{
	override def disableCrossPaths = true
	// ensure that interfaces are only Java sources and that they cannot reference Scala classes
	override def mainSources = descendents(mainSourceRoots, "*.java")
	override def compileOrder = CompileOrder.JavaThenScala
}
trait SourceProject extends BasicScalaProject
{
	override def disableCrossPaths = true
	override def packagePaths = mainResources +++ mainSources // the default artifact is a jar of the main sources and resources
}
trait ManagedBase extends BasicScalaProject
{
	override def deliverScalaDependencies = Nil
	override def managedStyle = ManagedStyle.Ivy
	override def useDefaultConfigurations = false
	val defaultConf = Configurations.Default
	val testConf = Configurations.Test
}
trait Component extends DefaultProject
{
	override def projectID = componentID match { case Some(id) => super.projectID extra("e:component" -> id); case None => super.projectID }
	def componentID: Option[String] = None
}