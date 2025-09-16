package io.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import core.model.agent.behavior.silence.SilenceEffects.SilenceEffect
import core.model.agent.behavior.silence.SilenceStrategies.SilenceStrategy
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.simulation.actors.{AgentStateLoad, NeighborsLoad, NetworkResult}
import core.simulation.config.*
import io.persistence.actors.{NeighborStructure, StaticData}
import io.serialization.binary.Decoder
import io.web.{CustomAgentsData, CustomNeighborsData}
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import utils.logging.Logger

import java.io.{ByteArrayInputStream, PrintWriter}
import java.sql.{Connection, PreparedStatement, Statement}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

object DatabaseManager {
    // ============================================================================
    // DATABASE CONNECTION CONFIGURATION
    // ============================================================================
    private val dbHost = sys.env.getOrElse("DB_HOST", "localhost")
    private val dbHostLegacy = sys.env.getOrElse("DB_HOST_LEGACY", "localhost")
    
    private val dbPort = sys.env.getOrElse("DB_PORT", "5432")
    private val dbPortLegacy = sys.env.getOrElse("DB_PORT_LEGACY", "5432")
    
    private val dbUser = sys.env.getOrElse("DB_USER", "postgres")
    private val dbUserLegacy = sys.env.getOrElse("DB_USER_LEGACY", "postgres")
    
    private val dbPassword = sys.env.getOrElse("DB_PASSWORD", "postgres")
    private val dbPasswordLegacy = sys.env.getOrElse("DB_PASSWORD_LEGACY", "postgres")
    
    private val dbNameNew = sys.env.getOrElse("DB_NAME", "promueva")
    private val dbNameLegacy = sys.env.getOrElse("DB_NAME_LEGACY", "promueva_legacy")
    
    // ============================================================================
    // NEW DATABASE CONFIGURATION - Optimized for lighter workload
    // ============================================================================
    private val hikariConfigNew = new HikariConfig()
    
    hikariConfigNew.setJdbcUrl(s"jdbc:postgresql://$dbHost:$dbPort/$dbNameNew")
    hikariConfigNew.setUsername(dbUser)
    hikariConfigNew.setPassword(dbPassword)
    
    // Connection pool sizing
    hikariConfigNew.setMaximumPoolSize(16)
    hikariConfigNew.setMinimumIdle(4)
    hikariConfigNew.setMaxLifetime(3_600_000)
    hikariConfigNew.setConnectionTimeout(30_000)
    hikariConfigNew.setIdleTimeout(600_000)
    
    // PostgreSQL optimizations
    hikariConfigNew.addDataSourceProperty("cachePrepStmts", "true")
    hikariConfigNew.addDataSourceProperty("prepStmtCacheSize", "250")
    hikariConfigNew.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    hikariConfigNew.addDataSourceProperty("useServerPrepStmts", "true")
    hikariConfigNew.addDataSourceProperty("rewriteBatchedStatements", "true")
    hikariConfigNew.addDataSourceProperty("defaultRowFetchSize", "100")
    hikariConfigNew.addDataSourceProperty("logUnclosedConnections", "true")
    
    // ============================================================================
    // LEGACY DATABASE CONFIGURATION - Optimized for heavy workload
    // ============================================================================
    private val hikariConfigLegacy = new HikariConfig()
    
    hikariConfigLegacy.setJdbcUrl(s"jdbc:postgresql://$dbHostLegacy:$dbPortLegacy/$dbNameLegacy")
    hikariConfigLegacy.setUsername(dbUserLegacy)
    hikariConfigLegacy.setPassword(dbPasswordLegacy)
    
    // Connection pool sizing
    hikariConfigLegacy.setMaximumPoolSize(32)
    hikariConfigLegacy.setMinimumIdle(16)
    hikariConfigLegacy.setMaxLifetime(3_600_000)
    hikariConfigLegacy.setConnectionTimeout(60_000)
    hikariConfigLegacy.setIdleTimeout(900_000)
    
    // PostgreSQL optimizations
    hikariConfigLegacy.addDataSourceProperty("cachePrepStmts", "true")
    hikariConfigLegacy.addDataSourceProperty("prepStmtCacheSize", "500")
    hikariConfigLegacy.addDataSourceProperty("prepStmtCacheSqlLimit", "4096")
    hikariConfigLegacy.addDataSourceProperty("useServerPrepStmts", "true")
    hikariConfigLegacy.addDataSourceProperty("rewriteBatchedStatements", "true")
    hikariConfigLegacy.addDataSourceProperty("defaultRowFetchSize", "1000")
    hikariConfigLegacy.addDataSourceProperty("tcpKeepAlive", "true")
    
    // ============================================================================
    // DATA SOURCE INSTANCES
    // ============================================================================
    private val dataSource = new HikariDataSource(hikariConfigNew)
    private val dataSourceLegacy = new HikariDataSource(hikariConfigLegacy)
    
    private inline def getConnection: Connection = dataSource.getConnection
    private inline def getConnectionLegacy: Connection = dataSourceLegacy.getConnection
    
    // Inserts
    private def setPreparedStatementInt(stmt: PreparedStatement, parameterIndex: Int, value: Option[Int]): Unit = {
        value match {
            case Some(f) => stmt.setInt(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.INTEGER)
        }
    }
    
    private def setPreparedStatementFloat(stmt: PreparedStatement, parameterIndex: Int, value: Option[Float]): Unit = {
        value match {
            case Some(f) => stmt.setFloat(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.FLOAT)
        }
    }
    
    private def setPreparedStatementLong(stmt: PreparedStatement, parameterIndex: Int, value: Option[Long]): Unit = {
        value match {
            case Some(f) => stmt.setLong(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.BIGINT)
        }
    }
    
    private def setPreparedStatementString(stmt: PreparedStatement, parameterIndex: Int, value: Option[String]): Unit = {
        value match {
            case Some(str) => stmt.setString(parameterIndex, str)
            case None => stmt.setNull(parameterIndex, java.sql.Types.VARCHAR)
        }
    }
    
    /**
     * Saves a generated (procedurally created) simulation run to the database with its configuration and distributions.
     *
     * This method stores runs that are created through procedural generation rather than custom agent-by-agent
     * specification. It performs a transactional insert across three tables: <br>
     * 1. <code>generated_runs</code> - core simulation parameters (seed, density, limits, etc.) <br>
     * 2. <code>agent_type_distributions</code> - how many agents of each silence strategy/effect combination <br>
     * 3. <code>cognitive_bias_distributions</code> - how many agents exhibit each type of cognitive bias
     *
     * @param id                         Unique identifier for this simulation run (typically a Snowflake ID)
     * @param seed                       Random seed used for procedural generation (for reproducibility)
     * @param density                    Network density parameter controlling connection probability between agents
     * @param iterationLimit             Maximum number of simulation iterations before forced termination
     * @param totalNetworks              Number of separate networks to generate in this run
     * @param agentsPerNetwork           Number of agents in each network (calculated from agent type counts)
     * @param stopThreshold              Convergence threshold - simulation stops early if change falls below this
     * @param agentTypeDistributions     Array of (silenceStrategy, silenceEffect, count) tuples defining
     *                                   how many agents should have each combination of silence behaviors
     * @param cognitiveBiasDistributions Array of (biasType, count) tuples defining how many agents
     *                                   should exhibit each type of cognitive bias
     */
    def saveGeneratedRun(id: Long, seed: Long, density: Int, iterationLimit: Int,
        totalNetworks: Int, agentsPerNetwork: Int, stopThreshold: Float,
        agentTypeDistributions: Array[(SilenceStrategy, SilenceEffect, Int)],
        cognitiveBiasDistributions: Array[(Bias, Int)]): Unit = {
        if (GlobalState.APP_MODE.skipDatabase) return
        val connection = getConnection
        try {
            connection.setAutoCommit(false)
            val generatedRunsQuery =
                """
                INSERT INTO generated_runs (id, seed, density, iteration_limit, total_networks,
                                           agents_per_network, stop_threshold)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """
            
            val generatedRunsStmt = connection.prepareStatement(generatedRunsQuery)
            generatedRunsStmt.setLong(1, id)
            generatedRunsStmt.setLong(2, seed)
            generatedRunsStmt.setInt(3, density)
            generatedRunsStmt.setInt(4, iterationLimit)
            generatedRunsStmt.setInt(5, totalNetworks)
            generatedRunsStmt.setInt(6, agentsPerNetwork)
            generatedRunsStmt.setFloat(7, stopThreshold)
            generatedRunsStmt.executeUpdate()
            
            val agentTypeQuery =
                """
                INSERT INTO agent_type_distributions (run_id, silence_strategy, silence_effect, count)
                VALUES (?, CAST(? AS silence_strategy), CAST(? AS silence_effect), ?)
                """
            
            val agentTypeStmt = connection.prepareStatement(agentTypeQuery)
            agentTypeDistributions.foreach { case (silenceStrategy, silenceEffect, count) =>
                agentTypeStmt.setLong(1, id)
                agentTypeStmt.setString(2, silenceStrategy.name)
                agentTypeStmt.setString(3, silenceEffect.name)
                agentTypeStmt.setInt(4, count)
                agentTypeStmt.addBatch()
            }
            agentTypeStmt.executeBatch()
            
            val cognitiveBiasQuery =
                """
                INSERT INTO cognitive_bias_distributions (run_id, cognitive_bias, count)
                VALUES (?, CAST(? AS cognitive_bias), ?)
                """
            
            val cognitiveBiasStmt = connection.prepareStatement(cognitiveBiasQuery)
            cognitiveBiasDistributions.foreach { case (cognitiveBias, count) =>
                cognitiveBiasStmt.setLong(1, id)
                cognitiveBiasStmt.setString(2, cognitiveBias.name)
                cognitiveBiasStmt.setInt(3, count)
                cognitiveBiasStmt.addBatch()
            }
            cognitiveBiasStmt.executeBatch()
            
            connection.commit()
        } catch {
            case ex: Exception =>
                connection.rollback()
                Logger.logError(s"Error saving the run: ${ex.getMessage}")
        } finally {
            connection.setAutoCommit(true)
            connection.close()
        }
    }
    
    /**
     * Saves a custom simulation run to the database with all associated agent and neighbor data.
     *
     * This method performs a transactional insert across three tables: <br>
     * 1. <code>custom_runs</code> - basic run metadata <br>
     * 2. <code>custom_agents</code> - individual agent properties and behaviors <br>
     * 3. <code>custom_neighbors</code> - network connections and influences between agents
     *
     * @param id                  Unique identifier for this simulation run
     * @param iterationLimit      Maximum number of simulation iterations to run
     * @param stopThreshold       Convergence threshold to stop simulation early
     * @param runName             Human-readable name for this simulation run
     * @param customAgentsData    Container with all agent properties (beliefs, strategies, etc.)
     * @param customNeighborsData Container with network topology and influence data
     */
    def saveCustomRun(id: Long, iterationLimit: Int, stopThreshold: Float, runName: String,
        customAgentsData: CustomAgentsData, customNeighborsData: CustomNeighborsData): Unit = {
        if (GlobalState.APP_MODE.skipDatabase) return
        val connection = getConnection
        try {
            connection.setAutoCommit(false)
            
            val customRunsQuery =
                """
                  |INSERT INTO custom_runs (id, iteration_limit, stop_threshold, run_name)
                  |values (?, ?, ?, ?)
                  |""".stripMargin
            
            val customRunsStmt: PreparedStatement = connection.prepareStatement(customRunsQuery)
            customRunsStmt.setLong(1, id)
            customRunsStmt.setInt(2, iterationLimit)
            customRunsStmt.setFloat(3, stopThreshold)
            customRunsStmt.setString(4, runName)
            customRunsStmt.executeUpdate()
            
            val customAgentsQuery =
                """
                  |INSERT INTO custom_agents (run_id, silence_strategy, silence_effect, belief, tolerance_radius,
                  |                           tolerance_offset, name, majority_threshold, confidence)
                  |values (?, CAST(? AS silence_strategy), CAST(? AS silence_effect), ?, ?, ?, ?, ?, ?)
                  |""".stripMargin
            
            val customAgentsStmt: PreparedStatement = connection.prepareStatement(customAgentsQuery)
            for (i <- customAgentsData.belief.indices) {
                customAgentsStmt.setLong(1, id)
                customAgentsStmt.setString(2, customAgentsData.silenceStrategy(i).name)
                customAgentsStmt.setString(3, customAgentsData.silenceEffect(i).name)
                customAgentsStmt.setFloat(4, customAgentsData.belief(i))
                customAgentsStmt.setFloat(5, customAgentsData.radius(i))
                customAgentsStmt.setFloat(6, customAgentsData.offset(i))
                customAgentsStmt.setString(7, customAgentsData.name(i))
                
                customAgentsData.majorityThreshold match {
                    case Some(thresholdMap) =>
                        thresholdMap.get(i) match {
                            case Some(threshold) => customAgentsStmt.setFloat(8, threshold)
                            case None => customAgentsStmt.setNull(8, java.sql.Types.FLOAT)
                        }
                    case None => customAgentsStmt.setNull(8, java.sql.Types.FLOAT)
                }
                
                customAgentsData.confidence match {
                    case Some(confidenceMap) =>
                        confidenceMap.get(i) match {
                            case Some(conf) => customAgentsStmt.setFloat(9, conf)
                            case None => customAgentsStmt.setNull(9, java.sql.Types.FLOAT)
                        }
                    case None => customAgentsStmt.setNull(9, java.sql.Types.FLOAT)
                }
                
                customAgentsStmt.addBatch()
            }
            customAgentsStmt.executeBatch()
            
            val customNeighborsQuery =
                """
                  |INSERT INTO custom_neighbors (run_id, influence, cognitive_bias, source, target)
                  |values (?, ?, CAST(? AS cognitive_bias), ?, ?)
                  |""".stripMargin
            
            val customNeighborsStmt: PreparedStatement = connection.prepareStatement(customNeighborsQuery)
            
            for (i <- customNeighborsData.source.indices) {
                customNeighborsStmt.setLong(1, id)
                customNeighborsStmt.setFloat(2, customNeighborsData.influence(i))
                customNeighborsStmt.setString(3, customNeighborsData.bias(i).name)
                customNeighborsStmt.setInt(4, customNeighborsData.source(i))
                customNeighborsStmt.setInt(5, customNeighborsData.target(i))
                customNeighborsStmt.addBatch()
            }
            customNeighborsStmt.executeBatch()
            
            connection.commit()
        } catch {
            case ex: Exception =>
                connection.rollback()
                Logger.logError(s"Error saving the run: ${ex.getMessage}")
        } finally {
            connection.setAutoCommit(true)
            connection.close()
        }
    }
    
    /**
     * Saves the results of the different networks from a particular run to the database
     *
     * @param runID             Id of the run
     * @param networkResults    The Array with the individual results of each network
     */
    def submitNetworkResults(runID: Long, networkResults: mutable.ArrayBuffer[NetworkResult]): Unit = {
        if (GlobalState.APP_MODE.skipDatabase) return
        val connection = getConnection
        try {
            val query =
                """
                  |INSERT INTO network_results (run_id, build_time, run_time, network_number, final_round, reached_consensus)
                  |VALUES (?, ?, ?, ?, ?, ?)
                  |""".stripMargin
                
            val stmt = connection.prepareStatement(query)
            for (result <- networkResults) {
                stmt.setLong(1, runID)
                stmt.setLong(2, result.buildTime)
                stmt.setLong(3, result.runTime)
                stmt.setInt(4, result.networkNumber)
                stmt.setInt(5, result.finalRound)
                stmt.setBoolean(6, result.reachedConsensus)
                stmt.addBatch()
            }
            stmt.executeBatch()
            
        } catch {
            case ex: Exception =>
                Logger.logError(s"Error saving the runs: ${ex.getMessage}")
        } finally {
            connection.close()
        }
    }
    
    case class UserData(
        id: Long,
        firebaseUid: String,
        email: String,
        name: String,
        role: String,
        isActive: Boolean,
        createdAt: String,
        updatedAt: String
    )
    
    def createOrUpdateUser(firebaseUid: String, email: String, name: String, role: String = "Guest"): Option[UserData] = {
        val connection = getConnection
        try {
            val sql =
                """
                       INSERT INTO users (firebase_uid, email, name, role, created_at, updated_at, is_active)
                       VALUES (?::uuid, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, true)
                       ON CONFLICT (firebase_uid)
                       DO UPDATE SET
                           email = EXCLUDED.email,
                           name = EXCLUDED.name,
                           updated_at = CURRENT_TIMESTAMP
                       RETURNING id, firebase_uid, created_at, updated_at, role, email, name, is_active
                   """
            
            val stmt = connection.prepareStatement(sql)
            stmt.setString(1, firebaseUid)
            stmt.setString(2, email)
            stmt.setString(3, name)
            stmt.setString(4, role)
            
            val resultSet = stmt.executeQuery()
            
            if (resultSet.next()) {
                Some(UserData(
                    id = resultSet.getLong("id"),
                    firebaseUid = resultSet.getString("firebase_uid"),
                    email = resultSet.getString("email"),
                    name = resultSet.getString("name"),
                    role = resultSet.getString("role"),
                    isActive = resultSet.getBoolean("is_active"),
                    createdAt = resultSet.getTimestamp("created_at").toString,
                    updatedAt = resultSet.getTimestamp("updated_at").toString
                ))
            } else {
                None
            }
        } catch {
            case ex: Exception =>
                println(s"Error creating/updating user: ${ex.getMessage}")
                None
        } finally {
            connection.close()
        }
    }
    
    def getUserByFirebaseUid(firebaseUid: String): Option[UserData] = {
        val connection = getConnection
        try {
            val sql =
                """
                       SELECT id, firebase_uid, created_at, updated_at, role, email, name, is_active
                       FROM users
                       WHERE firebase_uid = ?::uuid
                   """
            
            val stmt = connection.prepareStatement(sql)
            stmt.setString(1, firebaseUid)
            
            val resultSet = stmt.executeQuery()
            
            if (resultSet.next()) {
                Some(UserData(
                    id = resultSet.getLong("id"),
                    firebaseUid = resultSet.getString("firebase_uid"),
                    email = resultSet.getString("email"),
                    name = resultSet.getString("name"),
                    role = resultSet.getString("role"),
                    isActive = resultSet.getBoolean("is_active"),
                    createdAt = resultSet.getTimestamp("created_at").toString,
                    updatedAt = resultSet.getTimestamp("updated_at").toString
                ))
            } else {
                None
            }
        } catch {
            case ex: Exception =>
                println(s"Error getting user: ${ex.getMessage}")
                None
        } finally {
            connection.close()
        }
    }
    
    def updateUserRole(firebaseUid: String, newRole: String): Boolean = {
        val connection = getConnection
        try {
            val sql =
                """
                       UPDATE users
                       SET role = ?, updated_at = CURRENT_TIMESTAMP
                       WHERE firebase_uid = ?::uuid
                   """
            
            val stmt = connection.prepareStatement(sql)
            stmt.setString(1, newRole)
            stmt.setString(2, firebaseUid)
            
            val rowsAffected = stmt.executeUpdate()
            rowsAffected > 0
        } catch {
            case ex: Exception =>
                println(s"Error updating user role: ${ex.getMessage}")
                false
        } finally {
            connection.close()
        }
    }
    
    /**
     * Legacy code to save the entire state to database use can be used when
     */
    
    // --------------------------------------------------------------
    def createRun(
                   runMode: Byte,
                   saveMode: Byte,
                   numberOfNetworks: Int,
                   density: Option[Int],
                   degreeDistribution: Option[Float],
                   stopThreshold: Float,
                   iterationLimit: Int,
                   initialDistribution: String
                 ): Option[Long] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        
        try {
            conn.setAutoCommit(false) 
            
            val sql = density match {
                case Some(_) => """
                WITH inserted_run AS (
                    INSERT INTO runs (
                        number_of_networks, iteration_limit, stop_threshold,
                        initial_distribution, run_mode, save_mode
                    ) 
                    VALUES (?, ?, ?, CAST(? AS initial_distribution), ?, ?)
                    RETURNING id
                )
                INSERT INTO generated_run_parameters (run_id, degree_distribution, density)
                SELECT id, ?, ?
                FROM inserted_run
                RETURNING run_id;
                """
                case None => """
                INSERT INTO runs (
                    number_of_networks, iteration_limit, stop_threshold,
                    initial_distribution, run_mode, save_mode
                ) 
                VALUES (?, ?, ?, CAST(? AS initial_distribution), ?, ?)
                RETURNING id;
                """
            }
            
            stmt = conn.prepareStatement(sql)
            
            // Common parameters
            stmt.setInt(1, numberOfNetworks)
            stmt.setInt(2, iterationLimit)
            stmt.setFloat(3, stopThreshold)
            stmt.setString(4, initialDistribution)
            stmt.setShort(5, 1.toShort)
            stmt.setShort(6, 1.toShort)
            
            // Additional parameters for generated runs
            density.foreach { d =>
                stmt.setFloat(7, degreeDistribution.getOrElse(
                    throw new IllegalArgumentException("Degree distribution required for generated runs")))
                stmt.setInt(8, d)
            }
            
            val rs = stmt.executeQuery()
            val result = if (rs.next()) Some(rs.getLong(1)) else None
            
            conn.commit()
            result
        } catch {
            case e: Exception =>
                conn.rollback()
                throw e
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) {
                conn.setAutoCommit(true)
                conn.close()
            }
        }
    }
    
    /*
    ToDo make function insert networks as a batch
     */
    def createNetwork
    (
      id: UUID,
      name: String,
      runId: Long,
      numberOfAgents: Int
    ): Unit = {
        val conn = getConnectionLegacy
        try {
            conn.setAutoCommit(true)
            val sql =
                """
                INSERT INTO networks (
                    id, run_id, number_of_agents, name
                ) VALUES (CAST(? AS uuid), ?, ?, ?) RETURNING id;
                """
            val stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
            stmt.setObject(1, id)
            stmt.setLong(2, runId)
            stmt.setInt(3, numberOfAgents)
            stmt.setString(4, name)
            
            stmt.executeUpdate()
            stmt.close()
        } finally {
            conn.close()
        }
    }
    
    def insertAgentsBatch(agents: StaticData): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            conn.setAutoCommit(true)
            val sql = """
            INSERT INTO public.agents (
                id, network_id, number_of_neighbors, tolerance_radius, tol_offset,
                silence_strategy, silence_effect, belief_update_method,
                expression_threshold, open_mindedness, name
            ) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS silence_strategy),
            CAST(? AS silence_effect), CAST(? AS belief_update_method), ?, ?, ?);
        """
            stmt = conn.prepareStatement(sql)
            
            var i = 0
            val size = agents.static.length
            while (i < size) {
                val agent = agents.static(i)
                stmt.setObject(1, agent.id)
                stmt.setObject(2, agents.networkId)
                stmt.setInt(3, agent.numberOfNeighbors)
                stmt.setFloat(4, agent.toleranceRadius)
                stmt.setFloat(5, agent.tolOffset)
                stmt.setString(6, agent.causeOfSilence)
                stmt.setString(7, agent.effectOfSilence)
                stmt.setString(8, agent.beliefUpdateMethod)
                setPreparedStatementFloat(stmt, 9, agent.beliefExpressionThreshold)
                setPreparedStatementInt(stmt, 10, agent.openMindedness)
                setPreparedStatementString(stmt, 11, agent.name)
                stmt.addBatch()
                i += 1
            }
            stmt.executeBatch()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    // ToDo optimize insert to maybe use copy or batched concurrency
    def insertNeighborsBatch(networkStructures: ArrayBuffer[NeighborStructure]): Unit = {
        val conn = getConnectionLegacy
        try {
            val sql =
                """
            INSERT INTO public.neighbors (
                source, target, value, cognitive_bias
            ) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS cognitive_bias));
            """
            val stmt = conn.prepareStatement(sql)
            var i = 0
            val size = networkStructures.size
            while (i < size) {
                val networkStructure = networkStructures(i)
                stmt.setObject(1, networkStructure.source)
                stmt.setObject(2, networkStructure.target)
                stmt.setFloat(3, networkStructure.value)
                stmt.setString(4, networkStructure.bias.name)
                stmt.addBatch()
                i += 1
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
        val conn = getConnectionLegacy
        var stmt: Statement = null
        try {
            stmt = conn.createStatement()
            stmt.execute(
                s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    agent_id uuid NOT NULL,
                    round integer NOT NULL,
                    belief real NOT NULL,
                    state_data bytea
                );
                """
            )
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    def insertRounds(dataOut: Array[Byte], tableName: String): Unit = {
        val conn = getConnectionLegacy
        var copyManager: CopyManager = null
        try {
            conn.setAutoCommit(false)
            createUnloggedTable(tableName)
            copyManager = new CopyManager(conn.unwrap(classOf[BaseConnection]))
            val reader = new ByteArrayInputStream(dataOut)
            copyManager.copyIn(s"COPY public.$tableName FROM STDIN WITH (FORMAT BINARY)", reader)
            conn.commit()
            reader.close()
        } catch {
            case e: Exception =>
                e.printStackTrace()
                throw e
        } finally {
            if (conn != null) conn.close()
        }
    }
    
    
    def flushRoundTable(tempTableName: String, targetTable: String): Unit = {
        val disableTriggersSql = s"ALTER TABLE public.$targetTable DISABLE TRIGGER ALL"
        val enableTriggersSql = s"ALTER TABLE public.$targetTable ENABLE TRIGGER ALL"
        val insertSql = s"""
                    INSERT INTO public.$targetTable (agent_id, round, belief, state_data)
                    SELECT agent_id, round, belief, state_data
                    FROM public.$tempTableName
                    ON CONFLICT (agent_id, round) DO NOTHING
                    """;
            
        val dropSql = s"DROP TABLE IF EXISTS public.$tempTableName"
        val conn = getConnectionLegacy
        var stmt: Statement = null
        try {
            conn.setAutoCommit(false)
            stmt = conn.createStatement()
            
            stmt.execute(disableTriggersSql)
            stmt.execute(insertSql)
            stmt.execute(enableTriggersSql)
            stmt.execute(dropSql)
            
            conn.commit()
        } catch {
            case e: org.postgresql.util.PSQLException
                if e.getMessage.contains("relation") && e.getMessage.contains("does not exist") ||
                  e.getMessage.contains("current transaction is aborted") =>
                conn.rollback()
            case e: Exception =>
                conn.rollback()
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    //Updates
    def updateTimeField(id: Either[Long, UUID], timeValue: Long, table: String, field: String): Unit = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql = id match {
                case Left(longID) => s"UPDATE $table SET $field = ? WHERE id = ?"
                case Right(uuid) => s"UPDATE $table SET $field = ? WHERE id = CAST(? AS uuid)"
            }
            
            stmt = conn.prepareStatement(sql)
            stmt.setLong(1, timeValue)
            
            id match {
                case Left(longID) => stmt.setLong(2, longID)
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
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql = s"UPDATE networks SET final_round = ?, simulation_outcome = ? WHERE id = CAST(? AS uuid)"
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, finalRound)
            stmt.setBoolean(2, simulationOutcome)
            stmt.setString(3, id.toString)
            stmt.executeUpdate()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    // Queries
    case class RunQueryResult(numberOfNetworks: Int, iterationLimit: Int, stopThreshold: Float, 
        distribution: String, density: Option[Int], degreeDistribution: Option[Float])
    
    def getRun(runId: Int): Option[RunQueryResult] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql = """
                  |SELECT * FROM runs LEFT JOIN
                  |generated_run_parameters on runs.id = generated_run_parameters.run_id
                  |WHERE id = ?;
                  |""".stripMargin
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            val queryResult = stmt.executeQuery()
            if (queryResult.next()) {
                return Some(RunQueryResult(
                    queryResult.getInt("number_of_networks"),
                    queryResult.getInt("iteration_limit"),
                    queryResult.getFloat("stop_threshold"),
                    queryResult.getString("initial_distribution"),
                    Option(queryResult.getObject("density")).map(_.toString.toInt),
                    Option(queryResult.getObject("degree_distribution")).map(_.toString.toFloat)
                    ))
            }
        } catch {
            case e: Exception =>
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getRunInfo(networkId: UUID): Option[(String, Option[Int], Option[Float])] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT initial_distribution, density, degree_distribution
                  |    FROM runs
                  |LEFT JOIN
                  |    generated_run_parameters on runs.id = generated_run_parameters.run_id
                  |WHERE id = (SELECT run_id FROM networks WHERE id = CAST(? AS uuid));
                  |""".stripMargin
            stmt.setObject(1, networkId)
            stmt = conn.prepareStatement(sql)
            val queryResult = stmt.executeQuery()
            if (queryResult.next()) {
                return Some(
                    (queryResult.getString("initial_distribution"),
                      Option(queryResult.getObject("density")).map(_.toString.toInt),
                      Option(queryResult.getObject("degree_distribution")).map(_.toString.toInt)))
            }
            
        } catch {
            case e: Exception =>
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getNetworks(runId: Int, numberOfNetworks: Int): Option[Array[UUID]] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql = s"SELECT id FROM networks WHERE run_id = ?"
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            val queryResult = stmt.executeQuery()
            val networkIdArray = new Array[UUID](numberOfNetworks)
            var i = 0
            while (queryResult.next()) {
                networkIdArray(i) = queryResult.getObject(1, classOf[UUID])
                i += 1
            }
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getAgents(networkId: UUID, numberOfAgents: Int): Option[
      Array[(UUID, Float, Float, Option[Float], Option[Integer])]] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT id, tolerance_radius, tol_offset, expression_threshold, open_mindedness
                  |FROM agents
                  |WHERE network_id = = CAST(? AS uuid)
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setObject(1, networkId)
            val queryResult = stmt.executeQuery()
            val resultArray = new Array[(UUID, Float, Float, Option[Float], Option[Integer])](numberOfAgents)
            var i = 0
            while (queryResult.next()) {
                resultArray(i) = (
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getFloat(2),
                  queryResult.getFloat(3),
                  Option(queryResult.getObject(4)).map(_.toString.toFloat),
                  Option(queryResult.getObject(5)).map(_.toString.toInt)
                )
                i += 1
            }
            if (i != 0) return Some(resultArray) 
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getAgentsWithState(networkId: UUID, numberOfAgents: Int): Option[
      Array[(UUID, Float, Float, Option[Float], Option[Integer],
        Float, Option[Array[Byte]])]] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT a.id,
                  |    tolerance_radius, 
                  |    tol_offset, 
                  |    expression_threshold, 
                  |    open_mindedness,
                  |	   belief,
                  |	   state_data
                  |FROM agents a
                  |JOIN agent_states_speaking s ON a.id = s.agent_id
                  |WHERE a.network_id = CAST(? AS uuid) AND round = 0; 
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setObject(1, networkId)
            val queryResult = stmt.executeQuery()
            val resultArray = new Array[(UUID, Float, Float, Option[Float], Option[Integer], 
              Float, Option[Array[Byte]])](numberOfAgents)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray(i) = (
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getFloat(2),
                  queryResult.getFloat(3),
                  Option(queryResult.getObject(4)).map(_.toString.toFloat),
                  Option(queryResult.getObject(5)).map(_.toString.toInt),
                  queryResult.getFloat(6),
                  Option(queryResult.getBytes(7))
                )
                i += 1
            }
            if (i != 0) return Some(resultArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getNeighbors(networkId: UUID, numberOfAgents: Int): Option[Array[(UUID, UUID, Float, 
      Option[Bias])]] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT n.*
                  |FROM agents a
                  |JOIN neighbors n ON a.id = n.source
                  |WHERE a.network_id = CAST(? AS uuid);
                  |
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setObject(1, networkId)
            val queryResult = stmt.executeQuery()
            val resultArray = new ArrayBuffer[(UUID, UUID, Float, Option[Bias])](numberOfAgents)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray(i) = (
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getObject(2, classOf[UUID]),
                  queryResult.getFloat(3),
                  CognitiveBiases.fromString(queryResult.getString(4))
                )
                i += 1
            }
            if (i != 0) return Some(resultArray.toArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getAgentsWithState(runId: Int, numberOfAgents: Int, limit: Int, offset: Int): Option[
      Array[AgentStateLoad]] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |WITH limited_networks AS (
                  |    SELECT n.id
                  |    FROM networks n
                  |    WHERE run_id = ?
                  |    LIMIT ?
                  |    OFFSET ?
                  |)
                  |SELECT (n.id) as network_id,
                  |    a.id, 
                  |    belief,
                  |    tolerance_radius, 
                  |    tol_offset, 
                  |    state_data,
                  |    expression_threshold, 
                  |    open_mindedness
                  |FROM agents a
                  |JOIN agent_states_speaking s ON a.id = s.agent_id AND round = 0
                  |JOIN networks n ON n.id = a.network_id
                  |WHERE n.id IN (SELECT id FROM limited_networks)
                  |ORDER BY n.id;
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            val queryResult = stmt.executeQuery()
            val resultArray = new Array[AgentStateLoad](numberOfAgents * limit)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray(i) = AgentStateLoad(
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getObject(2, classOf[UUID]),
                  queryResult.getFloat(3),
                  queryResult.getFloat(4),
                  queryResult.getFloat(5),
                  Option(queryResult.getBytes(6)),
                  Option(queryResult.getObject(7)).map(_.toString.toFloat),
                  Option(queryResult.getObject(8)).map(_.toString.toInt)
                )
                i += 1
            }
            if (i != 0) return Some(resultArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getNeighbors(runId: Int, numberOfAgents: Int, limit: Int, offset: Int): Option[Array[NeighborsLoad]] = {
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |WITH limited_networks AS (
                  |    SELECT net.id
                  |    FROM networks net
                  |    WHERE run_id = ?
                  |    LIMIT ?
                  |	   OFFSET ?
                  |)
                  |SELECT net.id as network_id, n.*
                  |FROM agents a
                  |JOIN neighbors n ON a.id = n.source
                  |JOIN networks net ON net.id = a.network_id
                  |JOIN limited_networks ln ON ln.id = net.id
                  |ORDER BY net.id, a.id;
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            stmt.setInt(2, limit)
            stmt.setInt(3, offset)
            val queryResult = stmt.executeQuery()
            val resultArray = new ArrayBuffer[NeighborsLoad](numberOfAgents * 2)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray.addOne(NeighborsLoad(
                    queryResult.getObject(1, classOf[UUID]),
                    queryResult.getObject(2, classOf[UUID]),
                    queryResult.getObject(3, classOf[UUID]),
                    queryResult.getFloat(4),
                    CognitiveBiases.fromString(queryResult.getString(5)).get
                ))
                i += 1
            }
            if (i != 0) return Some(resultArray.toArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def exportToMaudeTXT(name: String, numberOfAgents: Int): Unit = {
        case class AgentRoundQuery(
            agentId: UUID,
            name: String,
            numberOfNeighbors: Int,
            toleranceRadius: Float,
            tolOffset: Float,
            //        silenceStrategy: String,
            //        silenceEffect: String,
            //        beliefUpdateMethod: String,
            //        expressionThreshold: Option[Float],
            //        openMindedness: Option[Int],
            round: Int,
            belief: Float,
            stateData: Array[Byte],
            isSpeaking: Boolean
        )
        val conn = getConnectionLegacy
        var stmt: PreparedStatement = null

        val ids = mutable.HashMap[String, Int]()
        val writer = new PrintWriter(name)
        try {
            val sql = """
                        |WITH RECURSIVE latest_run AS (
                        |    SELECT id 
                        |    FROM runs 
                        |    ORDER BY id DESC 
                        |    LIMIT 1
                        |),
                        |latest_network AS (
                        |    SELECT id as network_id
                        |    FROM networks 
                        |    WHERE run_id = (SELECT id FROM latest_run)
                        |    ORDER BY final_round DESC
                        |    LIMIT 1
                        |),
                        |agent_states AS (
                        |    SELECT 
                        |        agent_id,
                        |        round,
                        |        belief,
                        |        state_data,
                        |        true as speaking
                        |    FROM agent_states_speaking
                        |    WHERE agent_id IN (
                        |        SELECT id 
                        |        FROM agents 
                        |        WHERE network_id = (SELECT network_id FROM latest_network)
                        |    )
                        |    UNION ALL
                        |    SELECT 
                        |        agent_id,
                        |        round,
                        |        belief,
                        |        state_data,
                        |        false as speaking
                        |    FROM agent_states_silent
                        |    WHERE agent_id IN (
                        |        SELECT id 
                        |        FROM agents 
                        |        WHERE network_id = (SELECT network_id FROM latest_network)
                        |    )
                        |)
                        |SELECT 
                        |    a.id as agent_id,
                        |    a.name,
                        |    a.number_of_neighbors,
                        |    a.tolerance_radius,
                        |    a.tol_offset,
                        |    a.silence_strategy,
                        |    a.silence_effect,
                        |    a.belief_update_method,
                        |    a.expression_threshold,
                        |    a.open_mindedness,
                        |    s.round,
                        |    s.belief,
                        |    s.state_data,
                        |    s.speaking
                        |FROM latest_network ln
                        |JOIN agents a ON a.network_id = ln.network_id
                        |JOIN agent_states s ON s.agent_id = a.id
                        |ORDER BY s.round, a.id;
                        |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            val rs = stmt.executeQuery()
            
            var printResults = true
            var i = 0
            writer.print("< ")
            while (rs.next()) {
                val result = AgentRoundQuery(
                    agentId = UUID.fromString(rs.getString("agent_id")),
                    name = Option(rs.getString("name")).getOrElse(""),
                    numberOfNeighbors = rs.getInt("number_of_neighbors"),
                    toleranceRadius = rs.getFloat("tolerance_radius"),
                    tolOffset = rs.getFloat("tol_offset"),
//                    silenceStrategy = rs.getString("silence_strategy"),
//                    silenceEffect = rs.getString("silence_effect"),
//                    beliefUpdateMethod = rs.getString("belief_update_method"),
//                    expressionThreshold = Option(rs.getFloat("expression_threshold")).filterNot(_ => rs.wasNull()),
//                    openMindedness = Option(rs.getInt("open_mindedness")).filterNot(_ => rs.wasNull()),
                    round = rs.getInt("round"),
                    belief = rs.getFloat("belief"),
                    stateData = rs.getBytes("state_data"),
                    isSpeaking = rs.getBoolean("speaking")
                    )
                
                if (i == (numberOfAgents - 1)) {
                    if (printResults) {
                        writer.print(
                            s"${i + 1} : [ " +
                              s"${result.belief}, " +
                              s"${result.isSpeaking}, " +
                              s"${if (rs.getString("silence_effect") == "Memory") Decoder.decode(result.stateData)(0) + ", " else ""}" +
                              s"${result.toleranceRadius}] >"
                            )
                        
                    }
                    printResults = false
                }
                
                if (printResults) {
                    writer.print(s"${i + 1} : [ ${result.belief}, ${result.isSpeaking}, " +
                                   s"${if (rs.getString("silence_effect") == "Memory") Decoder.decode(result.stateData)(0) + ", " else ""}" +
                                   s"${result.toleranceRadius}], ")
                }
                
                ids.put(result.agentId.toString, i + 1)
                i = (i + 1) % numberOfAgents
            }
            
            val neighborSQL =
                """
                  |WITH RECURSIVE latest_run AS (
                  |    SELECT id
                  |    FROM runs
                  |    ORDER BY id DESC
                  |    LIMIT 1
                  |),
                  |latest_network AS (
                  |    SELECT id as network_id
                  |    FROM networks
                  |    WHERE run_id = (SELECT id FROM latest_run)
                  |    ORDER BY final_round DESC
                  |    LIMIT 1
                  |)
                  |SELECT * FROM neighbors WHERE source IN (
                  |        SELECT id
                  |        FROM agents
                  |        WHERE network_id = (SELECT network_id FROM latest_network)
                  |    )
                  |""".stripMargin
            stmt = conn.prepareStatement(neighborSQL)
            val neighbors = stmt.executeQuery()
            neighbors.next()
            writer.print(s"\n\n< (${ids(neighbors.getString(1))} , " +
                           s"${ids(neighbors.getString(2))}) : " +
                           s"${neighbors.getFloat(3)} >")
            while (neighbors.next()) {
                writer.print(s", < (${ids(neighbors.getString(1))} , " +
                               s"${ids(neighbors.getString(2))}) : " +
                               s"${neighbors.getFloat(3)} >")
            }
            
        } catch {
            case e: Exception =>
                e.printStackTrace()
                throw e
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
            writer.close()
        }
    }
    
}
