library(RPostgres)
library(data.table)
library(pacman)
library(ggplot2)

# Create a new DBI connection
conn <- dbConnect(RPostgres::Postgres(),
                  dbname = "promueva",
                  host = "localhost",
                  port = 5432,
                  user = "postgres",
                  password = "postgres")

# Query the database
query <- "SELECT (agent_id, round, belief, confidence, is_speaking) FROM round_data;"
result <- dbGetQuery(conn, query)
data_table_result <- as.data.table(result)

print(data_table_result)

# Disconnect
dbDisconnect(conn)

data_table_result$is_speaking <- as.factor(data_table_result$is_speaking)

# Create the ggplot
plot <- ggplot(data_table_result[round < 25, ], aes(x = round, y = belief, group = agent_id, color = is_speaking)) +
  geom_line() +
  labs(title = "Evolution of Agent Beliefs Over Rounds",
       x = "Round",
       y = "Belief",
       color = "Speaking Status") +
  scale_color_manual(values = c("TRUE" = "blue", "FALSE" = "red"),
                     labels = c("Silent", "Speaking")) +
  theme_minimal()

# Print the plot
print(plot)

conf_plot <- ggplot(data_table_result, aes(x = round, y = confidence, group = agent_id, color = is_speaking)) +
  geom_line() +
  labs(title = "Evolution of Agent Beliefs Over Rounds",
       x = "Round",
       y = "Confidence",
       color = "Speaking Status") +
  scale_color_manual(values = c("TRUE" = "blue", "FALSE" = "red"),
                     labels = c("Silent", "Speaking")) +
  theme_minimal()

# Print the plot
print(conf_plot)

plot_save = plot

data_table_result[]
max(data_table_result$round)