package worker

import java.io.File
import java.util.concurrent.CountDownLatch

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Cluster
import akka.persistence.cassandra.testkit.CassandraLauncher
import com.typesafe.config.{ Config, ConfigFactory }

object Main {

  // note that 2551 and 2552 are expected to be seed nodes though, even if
  // the back-end starts at 2000
  val backEndPortRange = 2000 to 2999

  val frontEndPortRange = 3000 to 3999

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case None =>
        startClusterInSameJvm()

      case Some(portString) if portString.matches("""\d+""") =>
        val port = portString.toInt
        if (backEndPortRange.contains(port)) start(port, "back-end")
        else if (frontEndPortRange.contains(port)) start(port, "front-end")
        else start(port, "worker", args.lift(1).map(_.toInt).getOrElse(1))

      case Some("cassandra") =>
        startCassandraDatabase()
        println("Started Cassandra, press Ctrl + C to kill")
        new CountDownLatch(1).await()

    }
  }

  def startClusterInSameJvm(): Unit = {
    // FIXME
//    startCassandraDatabase()
    // two backend nodes
    start(2551, "back-end")
    start(2552, "back-end")
    // two front-end nodes
    start(3000, "front-end")
    start(3001, "front-end")
    // two worker nodes with two worker actors each
    start(5001, "worker", 2)
    start(5002, "worker", 2)
  }

  def start(port: Int, role: String, workers: Int = 2): Unit = {
    ActorSystem[Nothing](
      Behaviors.setup[Nothing](ctx => {
        val cluster = Cluster(ctx.system)
        if (cluster.selfMember.hasRole("back-end")) {
          MasterSingleton.init(ctx.system)
        }
        if (cluster.selfMember.hasRole("front-end")) {
          ctx.spawn(FrontEnd(), "front-end")
          ctx.spawn(WorkResultConsumer(), "consumer")
        }
        if (cluster.selfMember.hasRole("worker")) {
          val masterProxy = MasterSingleton.init(ctx.system)
          (1 to workers).foreach(n => ctx.spawn(Worker(masterProxy), s"worker-$n"))
        }
        Behaviors.empty
      }),
      "ClusterSystem",
      config(port, role))
  }

  def config(port: Int, role: String): Config =
    ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port=$port
      akka.cluster.roles=[$role]
    """).withFallback(ConfigFactory.load())

  /**
   * To make the sample easier to run we kickstart a Cassandra instance to
   * act as the journal. Cassandra is a great choice of backend for Akka Persistence but
   * in a real application a pre-existing Cassandra cluster should be used.
   */
  def startCassandraDatabase(): Unit = {
    val databaseDirectory = new File("target/cassandra-db")
    CassandraLauncher.start(databaseDirectory, CassandraLauncher.DefaultTestConfigResource, clean = false, port = 9042)

    // shut the cassandra instance down when the JVM stops
    sys.addShutdownHook {
      CassandraLauncher.stop()
    }
  }

}
