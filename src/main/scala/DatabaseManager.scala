import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import java.io.StringReader
import java.io.{ByteArrayOutputStream, DataOutputStream, ByteArrayInputStream}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, PreparedStatement, Statement}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer


object DatabaseManager {
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
    
    // Creation of unlogged tables:
    private def createUnloggedTableMemoryMajority(tableName: String): Unit = {
        executeSQL(
            s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    agent_id uuid NOT NULL,
                    round integer NOT NULL,
                    is_speaking boolean NOT NULL,
                    belief real NOT NULL,
                    public_belief real NOT NULL
                );
                """
        )
    }
    
    private def createUnloggedTableMemoryConfidence(tableName: String): Unit = {
        executeSQL(
            s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    agent_id uuid NOT NULL,
                    round integer NOT NULL,
                    is_speaking boolean NOT NULL,
                    confidence real NOT NULL,
                    opinion_climate real NOT NULL,
                    belief real,
                    public_belief real
                );
                """
        )
    }
    
    private def createUnloggedTableMemorylessMajority(tableName: String): Unit = {
        executeSQL(
            s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    agent_id uuid NOT NULL,
                    round integer NOT NULL,
                    is_speaking boolean NOT NULL,
                    self_influence real NOT NULL,
                    belief real NOT NULL
                );
                """
        )
    }
    
    private def createUnloggedTableMemorylessConfidence(tableName: String): Unit = {
        executeSQL(
            s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    agent_id uuid NOT NULL,
                    round integer NOT NULL,
                    is_speaking boolean NOT NULL,
                    confidence real NOT NULL,
                    opinion_climate real NOT NULL,
                    self_influence real NOT NULL,
                    belief real
                );
            """
        )
    }
    
    private def executeSQL(sql: String): Unit = {
        val conn = getConnection
        var stmt: Statement = null
        try {
            stmt = conn.createStatement()
            stmt.execute(sql)
        } finally {
            if (stmt != null) stmt.close()
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
                """
            )
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    // Inserting the batch data for each agent type
    private def insertBatchGenericBinary[T](tableName: String, entries: ArrayBuffer[T], toBinaryFunc: (T, DataOutputStream) => Unit): Unit = {
        val conn = getConnection
        var copyManager: CopyManager = null
        try {
            copyManager = new CopyManager(conn.unwrap(classOf[BaseConnection]))
            
            val baos = new ByteArrayOutputStream()
            val dos = new DataOutputStream(baos)
            
            // Write the file signature
            dos.writeBytes("PGCOPY\n")
            dos.write(0xff)
            dos.write(0x0d)
            dos.write(0x0a)
            dos.write(0x00)
            // Write flags field
            dos.writeInt(0)
            // Write header extension area length
            dos.writeInt(0)
            
            entries.foreach { entry =>
                toBinaryFunc(entry, dos)
            }
            
            // Write file trailer
            dos.writeShort(-1)
            
            dos.flush()
            val byteArray = baos.toByteArray
            val reader = new ByteArrayInputStream(byteArray)
            
            copyManager.copyIn(s"COPY public.$tableName FROM STDIN WITH (FORMAT BINARY)", reader)
        } catch {
            case e: Exception =>
                e.printStackTrace()
                throw e
        } finally {
            if (conn != null) conn.close()
        }
    }
    
    private def writeBinaryUUID(dos: DataOutputStream, uuid: UUID): Unit = {
        dos.writeInt(16)
        val msb = uuid.getMostSignificantBits
        val lsb = uuid.getLeastSignificantBits
        dos.writeLong(msb)
        dos.writeLong(lsb)
    }
    
    private def writeBinaryBoolean(dos: DataOutputStream, b: Boolean): Unit = {
        dos.writeInt(1)
        dos.writeByte(if (b) 1 else 0)
    }
    
    private def writeBinaryInt(dos: DataOutputStream, i: Int): Unit = {
        dos.writeInt(4)
        dos.writeInt(i)
    }
    
    private def writeBinaryFloat(dos: DataOutputStream, f: Float): Unit = {
        dos.writeInt(4)
        dos.writeFloat(f)
    }
    
    private def writeBinaryOptionalFloat(dos: DataOutputStream, f: Option[Float]): Unit = {
        f match {
            case Some(value) => writeBinaryFloat(dos, value)
            case None => dos.writeInt(-1) // -1 indicates NULL in PostgreSQL binary format
        }
    }
    
    private def insertBatchMemoryConfidence(tableName: String, entries: ArrayBuffer[MemoryConfidenceRound]): Unit = {
        insertBatchGenericBinary(tableName, entries, (entry, dos) => {
            dos.writeShort(7)
            writeBinaryUUID(dos, entry.agentId)
            writeBinaryInt(dos, entry.round)
            writeBinaryBoolean(dos, entry.isSpeaking)
            writeBinaryFloat(dos, entry.confidence)
            writeBinaryFloat(dos, entry.opinionClimate)
            writeBinaryOptionalFloat(dos, entry.belief)
            writeBinaryOptionalFloat(dos, entry.publicBelief)
        })
    }
    
    private def insertBatchMemoryMajority(tableName: String, entries: ArrayBuffer[MemoryMajorityRound]): Unit = {
        insertBatchGenericBinary(tableName, entries, (entry, dos) => {
            dos.writeShort(5)
            writeBinaryUUID(dos, entry.agentId)
            writeBinaryInt(dos, entry.round)
            writeBinaryBoolean(dos, entry.isSpeaking)
            writeBinaryFloat(dos, entry.belief)
            writeBinaryFloat(dos, entry.publicBelief)
        })
    }
    
    private def insertBatchMemoryLessConfidence(tableName: String, entries: ArrayBuffer[MemoryLessConfidenceRound]): Unit = {
        insertBatchGenericBinary(tableName, entries, (entry, dos) => {
            dos.writeShort(7)
            writeBinaryUUID(dos, entry.agentId)
            writeBinaryInt(dos, entry.round)
            writeBinaryBoolean(dos, entry.isSpeaking)
            writeBinaryFloat(dos, entry.confidence)
            writeBinaryFloat(dos, entry.opinionClimate)
            writeBinaryFloat(dos, entry.selfInfluence)
            writeBinaryOptionalFloat(dos, entry.belief)
        })
    }
    
    private def insertBatchMemoryLessMajority(tableName: String, entries: ArrayBuffer[MemoryLessMajorityRound]): Unit = {
        insertBatchGenericBinary(tableName, entries, (entry, dos) => {
            dos.writeShort(5)
            writeBinaryUUID(dos, entry.agentId)
            writeBinaryInt(dos, entry.round)
            writeBinaryBoolean(dos, entry.isSpeaking)
            writeBinaryFloat(dos, entry.selfInfluence)
            writeBinaryFloat(dos, entry.belief)
        })
    }
    
    
    def insertBatchMemoryConfidenceRoundData(entries: ArrayBuffer[MemoryConfidenceRound], tempTableName: String): Unit = {
        createUnloggedTableMemoryConfidence(tempTableName)
        insertBatchMemoryConfidence(tempTableName, entries)
    }
    
    def insertBatchMemoryMajorityRoundData(entries: ArrayBuffer[MemoryMajorityRound], tempTableName: String): Unit = {
        createUnloggedTableMemoryMajority(tempTableName)
        insertBatchMemoryMajority(tempTableName, entries)
    }
    
    def insertBatchMemoryLessConfidenceRoundData(entries: ArrayBuffer[MemoryLessConfidenceRound], tempTableName: String): Unit = {
        createUnloggedTableMemorylessConfidence(tempTableName)
        insertBatchMemoryLessConfidence(tempTableName, entries)
    }
    
    def insertBatchMemoryLessMajorityRoundData(entries: ArrayBuffer[MemoryLessMajorityRound], tempTableName: String): Unit = {
        createUnloggedTableMemorylessMajority(tempTableName)
        insertBatchMemoryLessMajority(tempTableName, entries)
    }
    
    // Cleaning temp tables
    private def cleanTempTableGeneric(tempTableName: String, targetTable: String, columns: Seq[String]): Unit = {
        val columnList = columns.mkString(", ")
        val insertSql =
            s"""
            INSERT INTO public.$targetTable ($columnList)
            SELECT $columnList
            FROM public.$tempTableName
            ON CONFLICT (agent_id, round) DO NOTHING
            """
        val dropSql = s"DROP TABLE IF EXISTS public.$tempTableName"
        
        val conn = getConnection
        var stmt: Statement = null
        try {
            conn.setAutoCommit(false)
            stmt = conn.createStatement()
            
            // Execute INSERT
            stmt.execute(insertSql)
            
            // Execute DROP TABLE
            stmt.execute(dropSql)
            
            conn.commit()
        } catch {
            case e: Exception =>
                conn.rollback()
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    def cleanTempTableMemoryMajority(tempTableName: String): Unit = {
        cleanTempTableGeneric(
            tempTableName,
            "memory_majority_agents_data",
            Seq("agent_id", "round", "is_speaking", "belief", "public_belief")
        )
    }
    
    def cleanTempTableMemoryConfidence(tempTableName: String): Unit = {
        cleanTempTableGeneric(
            tempTableName,
            "memory_confidence_agents_data",
            Seq("agent_id", "round", "is_speaking", "confidence", "opinion_climate", "belief", "public_belief")
        )
    }
    
    def cleanTempTableMemorylessMajority(tempTableName: String): Unit = {
        cleanTempTableGeneric(
            tempTableName,
            "memoryless_majority_agents_data",
            Seq("agent_id", "round", "is_speaking", "self_influence", "belief")
        )
    }
    
    def cleanTempTableMemorylessConfidence(tempTableName: String): Unit = {
        cleanTempTableGeneric(
            tempTableName,
            "memoryless_confidence_agents_data",
            Seq("agent_id", "round", "is_speaking", "confidence", "opinion_climate", "self_influence", "belief")
        )
    }
    
    def cleanTempTable(tempTableName: String): Unit = {
        val conn = getConnection
        var stmt: Statement = null
        try {
            conn.setAutoCommit(false)
            stmt = conn.createStatement()
            
            // Copy to round_data table
            stmt.execute(
                s"""
                INSERT INTO public.round_data (
                    round, belief, is_speaking, confidence, opinion_climate, public_belief,
                    self_influence, agent_id
                )
                SELECT round, belief, is_speaking, confidence, opinion_climate, public_belief, self_influence, agent_id
                FROM public.$tempTableName
                ON CONFLICT (agent_id, round) DO NOTHING;
                """
            )
            
            // Drop temp table
            stmt.execute(s"DROP TABLE IF EXISTS public.$tempTableName;")
            conn.commit()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
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
    
    //Queries
    def reRunSpecificNetwork(id: UUID, agentType: AgentType): Array[AgentInitialState] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                s"""
                WITH InitialBeliefs AS (
                    SELECT
                        rd.agent_id,
                        rd.belief AS initial_belief
                    FROM
                        public.combined_round_data rd
                    JOIN
                        public.agents a ON rd.agent_id = a.id
                    WHERE
                        a.network_id = ?::uuid
                        AND rd.round = 0
                ),
                AgentTypes AS (
                    SELECT
                        id AS agent_id,
                        cause_of_silence,
                        effect_of_silence,
                        belief_update_method
                    FROM
                        public.agents
                    WHERE
                        network_id = ?::uuid
                ),
                Neighbors AS (
                    SELECT
                        ns.target AS agent_id,
                        STRING_AGG(ns.source::text, ',') AS neighbor_ids,
                        STRING_AGG(ns.value::text, ',') AS neighbor_values
                    FROM
                        public.networks_structure ns
                    JOIN
                        public.agents a ON ns.target = a.id
                    WHERE
                        a.network_id = ?::uuid
                    GROUP BY
                        ns.target
                )
                SELECT
                    ib.agent_id,
                    ib.initial_belief,
                    at.cause_of_silence,
                    at.effect_of_silence,
                    at.belief_update_method,
                    n.neighbor_ids,
                    n.neighbor_values
                FROM
                    InitialBeliefs ib
                JOIN
                    AgentTypes at ON ib.agent_id = at.agent_id
                LEFT JOIN
                    Neighbors n ON ib.agent_id = n.agent_id
                ORDER BY
                    ib.agent_id;
                """
            stmt = conn.prepareStatement(sql)
            stmt.setString(1, id.toString)
            stmt.setString(2, id.toString)
            stmt.setString(3, id.toString)
            val resultSet = stmt.executeQuery()
            
            val agentInitialStates = ArrayBuffer[AgentInitialState]()
            while (resultSet.next()) {
                val agentId = resultSet.getString("agent_id")
                val initialBelief = resultSet.getFloat("initial_belief")
                val neighborIds = Option(resultSet.getString("neighbor_ids")).getOrElse("").split(",").filter(_.nonEmpty)
                val neighborValues = Option(resultSet.getString("neighbor_values")).getOrElse("").split(",").filter(_.nonEmpty).map(_.toFloat)
                val neighbors = neighborIds.zip(neighborValues)
                
                val agentInitialState = AgentInitialState(agentId, initialBelief, agentType, neighbors)
                
                agentInitialStates += agentInitialState
            }
            
            return agentInitialStates.toArray
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        Array.empty[AgentInitialState]
    }
    
}
