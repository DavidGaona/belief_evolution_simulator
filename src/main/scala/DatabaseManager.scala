import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.{Connection, PreparedStatement, Statement}
import java.util.UUID
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import java.io.StringReader


class DatabaseManager {
    private val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl("jdbc:postgresql://localhost:5432/promueva")
    hikariConfig.setUsername("postgres")
    hikariConfig.setPassword("postgres")
    
    // Set maximum number of connections in the pool.
    hikariConfig.setMaximumPoolSize(26)
    
    // Set minimum number of idle connections in the pool.
    hikariConfig.setMinimumIdle(8)
    
    // Set the maximum lifetime of a connection in the pool.
    hikariConfig.setMaxLifetime(1800000) // 30 minutes
    
    // Set the connection timeout: how long the client will wait for a connection from the pool.
    hikariConfig.setConnectionTimeout(30000) // 30 seconds
    
    // Set the idle timeout: how long a connection can remain idle before being closed.
    hikariConfig.setIdleTimeout(600000) // 10 minutes
    
    // Additional properties for prepared statement caching.
    hikariConfig.addDataSourceProperty("cachePrepStmts", "true")
    hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250")
    hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    
    private val dataSource = new HikariDataSource(hikariConfig)
    
    private def getConnection: Connection = dataSource.getConnection
    
    // Inserts
    
    private def setPreparedStatementFloat(stmt: PreparedStatement, parameterIndex: Int, value: Option[Float]): Unit = {
        value match {
            case Some(f) => stmt.setFloat(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.FLOAT)
        }
    }
    
    private def setPreparedStatementLong(stmt: PreparedStatement, parameterIndex: Int, value: Option[Long]): Unit = {
        value match {
            case Some(f) => stmt.setFloat(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.BIGINT)
        }
    }
    
    def createRun
    (
      numberOfNetworks: Int,
      density: Option[Int],
      degreeDistribution: Option[Float],
      stopThreshold: Float,
      iterationLimit: Int,
      initialDistribution: String,
      runTime: Option[Long],
      buildTime: Option[Long]
    ): Option[Int] = {
        val conn = getConnection
        try {
            val sql =
                """
                INSERT INTO runs (
                    number_of_networks, density, degree_distribution, stop_threshold,
                    iteration_limit, initial_distribution, run_time, build_time
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS initial_distribution), ?, ?)
                """
            val stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
            stmt.setInt(1, numberOfNetworks)
            stmt.setObject(2, density.orNull)
            setPreparedStatementFloat(stmt, 3, degreeDistribution)
            stmt.setFloat(4, stopThreshold)
            stmt.setInt(5, iterationLimit)
            stmt.setString(6, initialDistribution)
            stmt.setObject(7, runTime.orNull)
            stmt.setObject(8, buildTime.orNull)
            
            stmt.executeUpdate()
            val rs = stmt.getGeneratedKeys
            if (rs.next()) Some(rs.getInt(1)) else None
        } finally {
            conn.close()
        }
    }
    
    /*
    ToDo make function insert networks as a batch
     */
    def createNetwork
    (
      id: UUID,
      name: String,
      runId: Int,
      numberOfAgents: Int,
      finalRound: Option[Int] = None,
      runTime: Option[Long] = None,
      buildTime: Option[Long] = None
    ): Unit = {
        val conn = getConnection
        try {
            val sql =
                """
                INSERT INTO networks (
                    id, name, run_id, number_of_agents, final_round, run_time, build_time
                ) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, ?) RETURNING id;
                """
            val stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
            stmt.setString(1, id.toString)
            stmt.setString(2, name)
            stmt.setInt(3, runId)
            stmt.setInt(4, numberOfAgents)
            stmt.setObject(5, finalRound.orNull)
            stmt.setObject(6, runTime.orNull)
            stmt.setObject(7, buildTime.orNull)
            
            stmt.executeUpdate()
            stmt.close()
        } finally {
            conn.close()
        }
    }
    
    def insertAgentsBatch(agents: Array[StaticAgentData]): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
            INSERT INTO public.agents (
                id, network_id, number_of_neighbors, tolerance_radius, tol_offset, belief_expression_threshold,
                cause_of_silence, effect_of_silence, belief_update_method
            ) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, CAST(? AS cause_of_silence),
                      CAST(? AS effect_of_silence), CAST(? AS belief_update_method));
            """
            stmt = conn.prepareStatement(sql)
            
            agents.foreach { agent =>
                stmt.setString(1, agent.id.toString)
                stmt.setObject(2, agent.networkId)
                stmt.setInt(3, agent.numberOfNeighbors)
                stmt.setFloat(4, agent.toleranceRadius)
                stmt.setFloat(5, agent.tolOffset)
                setPreparedStatementFloat(stmt, 6, agent.beliefExpressionThreshold)
                stmt.setString(7, agent.causeOfSilence)
                stmt.setString(8, agent.effectOfSilence)
                stmt.setString(9, agent.beliefUpdateMethod)
                stmt.addBatch()
            }
            stmt.executeBatch()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    def insertNetworkStructureBatch(networkStructures: Array[NetworkStructure]): Unit = {
        val conn = dataSource.getConnection
        try {
            val sql =
                """
                INSERT INTO public.networks_structure (
                    source, target, value
                ) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?);
                """
            val stmt = conn.prepareStatement(sql)
            
            networkStructures.foreach { networkStructure =>
                stmt.setString(1, networkStructure.source.toString)
                stmt.setString(2, networkStructure.target.toString)
                stmt.setFloat(3, networkStructure.value)
                stmt.addBatch()
            }
            
            stmt.executeBatch()
            stmt.close()
        } catch {
            case e: Exception =>
                e.printStackTrace() // Properly handle exceptions
        } finally {
            if (conn != null) conn.close()
        }
    }
    
    private def createUnloggedTable(tableName: String): Unit = {
        val conn = getConnection
        var stmt: Statement = null
        try {
            stmt = conn.createStatement()
            stmt.execute(
                s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    round integer NOT NULL,
                    belief real NOT NULL,
                    is_speaking boolean NOT NULL,
                    confidence real,
                    opinion_climate real,
                    public_belief real,
                    self_influence real NOT NULL,
                    agent_id uuid NOT NULL
                );
            """)
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    private def insertBatchIntoUnloggedTable(tableName: String, roundDataEntries: ArrayBuffer[RoundData]): Unit = {
        val conn = getConnection
        var copyManager: CopyManager = null
        try {
            copyManager = new CopyManager(conn.unwrap(classOf[BaseConnection]))
            
            val data = new StringBuilder
            roundDataEntries.foreach { roundData =>
                data.append(s"${roundData.round},${roundData.belief},${roundData.isSpeaking},")
                data.append(s"${roundData.confidence.map(_.toString).getOrElse("")},")
                data.append(s"${roundData.opinionClimate.map(_.toString).getOrElse("")},")
                data.append(s"${roundData.publicBelief.map(_.toString).getOrElse("")},")
                data.append(s"${roundData.selfInfluence},${roundData.agentId}\n")
            }
            
            val reader = new StringReader(data.toString())
            copyManager.copyIn(s"COPY public.$tableName FROM STDIN WITH (FORMAT CSV)", reader)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (conn != null) conn.close()
        }
    }
    
    private def copyDataFromTempTable(tableName: String): Unit = {
        val conn = getConnection
        var stmt: Statement = null
        try {
            conn.setAutoCommit(false) // Start transaction
            stmt = conn.createStatement()
            // Copy data from the temp table to the main table
            stmt.execute(
                s"""
            INSERT INTO public.round_data (
                round, belief, is_speaking, confidence, opinion_climate, public_belief, self_influence, agent_id
            )
            SELECT round, belief, is_speaking, confidence, opinion_climate, public_belief, self_influence, agent_id
            FROM public.$tableName
            ON CONFLICT (agent_id, round) DO NOTHING;
        """)
            
            // Truncate the temp table to delete its data
            stmt.execute(s"TRUNCATE TABLE public.$tableName;")
            
            conn.commit() // Commit transaction
        } catch {
            case e: Exception =>
                e.printStackTrace()
                if (conn != null) conn.rollback() // Rollback transaction so as to not lose data
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    
    
    def insertBatchRoundData(roundDataEntries: ArrayBuffer[RoundData], tempTableName: String): Unit = {
        createUnloggedTable(tempTableName)
        insertBatchIntoUnloggedTable(tempTableName, roundDataEntries)
        copyDataFromTempTable(tempTableName)
    }
    
    def cleanTempTable(tempTableName: String): Unit = {
        val conn = getConnection
        var stmt: Statement = null
        try {
            stmt = conn.createStatement()
            stmt.execute(s"DROP TABLE IF EXISTS public.$tempTableName;")
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
//    def insertBatchRoundData(roundDataEntries: ArrayBuffer[RoundData]): Unit = {
//        val conn = getConnection
//        var stmt: PreparedStatement = null
//        try {
//            val sql =
//                """
//                INSERT INTO public.round_data (
//                    round, belief, is_speaking, confidence, opinion_climate, public_belief, self_influence, agent_id
//                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid));
//                """
//            stmt = conn.prepareStatement(sql)
//
//            roundDataEntries.foreach { roundData =>
//                stmt.setInt(1, roundData.round)
//                stmt.setFloat(2, roundData.belief)
//                stmt.setBoolean(3, roundData.isSpeaking)
//                setPreparedStatementFloat(stmt, 4, roundData.confidence)
//                setPreparedStatementFloat(stmt, 5, roundData.opinionClimate)
//                setPreparedStatementFloat(stmt, 6, roundData.publicBelief)
//                stmt.setFloat(7, roundData.selfInfluence)
//                stmt.setString(8, roundData.agentId.toString)
//                stmt.addBatch()
//            }
//
//            stmt.executeBatch()
//        } catch {
//            case e: Exception => e.printStackTrace()
//        } finally {
//            if (stmt != null) stmt.close()
//            if (conn != null) conn.close()
//        }
//    }
    
    //Updates
    def updateTimeField(id: Either[Int, UUID], timeValue: Long, table: String, field: String): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql = id match {
                case Left(intId) => s"UPDATE $table SET $field = ? WHERE id = ?"
                case Right(uuid) => s"UPDATE $table SET $field = ? WHERE id = CAST(? AS uuid)"
            }
            
            stmt = conn.prepareStatement(sql)
            stmt.setLong(1, timeValue)
            
            id match {
                case Left(intId) => stmt.setInt(2, intId)
                case Right(uuid) => stmt.setString(2, uuid.toString)
            }
            
            stmt.executeUpdate()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    
    def updateNetworkFinalRound(id: UUID, finalRound: Int, simulationOutcome: Boolean): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql = s"UPDATE networks SET final_round = ?, simulation_outcome = ? WHERE id = CAST(? AS uuid)"
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, finalRound)
            stmt.setBoolean(2, simulationOutcome)
            stmt.setString(3, id.toString)
            stmt.executeUpdate()
            stmt.close()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            conn.close()
        }
    }
}
