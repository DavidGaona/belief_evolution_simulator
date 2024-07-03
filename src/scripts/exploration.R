library(RPostgres)
library(data.table)
library(pacman)
library(ggplot2)
library(ggthemes)
library(scales)
library(plotly)
library(viridis)
library(hrbrthemes)
library(igraph)
library(ggraph)
library(dplyr)

# Create a new DBI connection
conn <- dbConnect(RPostgres::Postgres(),
                  dbname = "promueva",
                  host = "localhost",
                  port = 5432,
                  user = "postgres",
                  password = "postgres")

q1 <- "
select
	*
from
	combined_round_data
WHERE
	network_id = '01907522-ba3a-7000-91ea-8d37007cf368';
"
# 01907514-c458-7000-a159-ba7daa46a1e5

data_table_result <- as.data.table(dbGetQuery(conn, q1))

# Query the database
networks_id <- "
SELECT
    id, name
FROM
    networks
WHERE
    run_id = 15 AND not simulation_outcome
ORDER BY id;
"
networks <- dbGetQuery(conn, networks_id)
networks_beyond_round_0 <- list()

for (i in seq_along(networks)) {
  network_id <- networks[i, ]$id
  query <- sprintf("
    SELECT
        rd.*
    FROM
        public.round_data rd
    JOIN
        public.agents a ON rd.agent_id = a.id
    WHERE
        a.network_id = '%s'
    ORDER BY round;
  ", network_id)

  query2 <-  sprintf("
    SELECT * FROM networks_structure WHERE source in (
    	SELECT
    	    id
    	FROM
    	    agents
    	WHERE
    	    network_id = '%s'
    );
  ", network_id)
  data_table_result <- as.data.table(dbGetQuery(conn, query))
  network_structure <- as.data.table(dbGetQuery(conn, query2))

  local_max_round <- data_table_result[
    is_speaking == TRUE,
    .(last_speaking_round = max(round)),
    by = agent_id
  ]

  if (any(local_max_round$last_speaking_round > 0)) {
    networks_beyond_round_0 <- c(networks_beyond_round_0, network_id)
  }
}

print(networks_beyond_round_0)
dbDisconnect(conn)



data_table_result$is_speaking <- as.factor(data_table_result$is_speaking)
#data_table_result <- data_table_result[order(agent_id)]
initial_beliefs <- data_table_result[round == 0, .(agent_id, initial_belief = belief)]
data_table_result <- data_table_result[initial_beliefs, on = "agent_id"][order(-initial_belief, round)]
data_table_result <- data_table_result[order(round)]

data_table_result[, agent_label := paste("Agent", .GRP), by = agent_id]
max(data_table_result$round)

# Filter data to reduce overplotting
# <- data_table_result[round <= 5000 & round %% 50 == 0]
max_round_val <- 150
rounds <- min(max_round_val, max(data_table_result$round))
filtered_data <- data_table_result[round <= rounds]
#filtered_data <- data_table_result[round <= rounds & round %% 5 == 0]


# Prepare the data
melted_data <- melt(filtered_data,
                    id.vars = c("round", "agent_id", "agent_label", "is_speaking"),
                    measure.vars = c("belief", "public_belief"),
                    variable.name = "belief_type", value.name = "value")

# Rename belief types for clarity
melted_data$belief_type <- factor(melted_data$belief_type,
                                  levels = c("belief", "public_belief"),
                                  labels = c("Private Belief", "Public Belief"))

# Vibrant color palette
new_palette <- c("#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2")
tableau_palette <- c("#006BA2", "#FF800E", "#ABABAB", "#595959", "#5F9ED1", "#C85200", "#898989")
muted_palette <- c(
  "#4878D0",  # Blue
  "#EE854A",  # Orange
  "#6ACC64",  # Green
  "#D65F5F",  # Red
  "#956CB4",  # Purple
  "#8C613C",  # Brown
  "#DC7EC0",  # Pink
  "#45B4C2",  # Teal
  "#CEA93E",  # Gold
  "#7F7F7F"   # Gray
)

# Create the plot
p_publication <- ggplot(melted_data,
                        aes(x = round, y = value,
                            group = interaction(agent_label, belief_type),
                            color = agent_label)) +
  geom_line(aes(linetype = belief_type), size = 1) +
  geom_point(aes(shape = is_speaking), size = 3) +
  labs(title = "Evolution of Agent Beliefs and Speaking Status Over Rounds",
       subtitle = "Sub title pending",
       x = "Round",
       y = "Value",
       color = "Agent",
       linetype = "Measure",
       shape = "Speaking Status") +
  scale_color_manual(values = muted_palette) +
  scale_linetype_manual(values = c("Private Belief" = "solid", "Public Belief" = "dashed")) +
  scale_shape_manual(values = c("TRUE" = 16, "FALSE" = 1),
                     labels = c("Silent", "Speaking")) +
  scale_x_continuous(breaks = seq(0, rounds+1, by = 5), limits = c(-1, rounds + 1), expand = c(0, 0)) +
  scale_y_continuous(limits = c(0, 1), breaks = seq(0, 1, 0.1), expand = c(0.01, 0)) +
  theme_minimal(base_size = 12) +
  theme(
    plot.title = element_text(size = 16, face = "bold", hjust = 0.5),
    plot.subtitle = element_text(size = 12, hjust = 0.5),
    axis.title = element_text(size = 12, face = "bold"),
    axis.text = element_text(size = 10),
    legend.title = element_text(size = 10, face = "bold"),
    legend.text = element_text(size = 9),
    legend.position = "right",
    legend.box = "vertical",
    panel.grid.major = element_line(color = "grey90", size = 0.1),
    panel.grid.minor = element_blank(),
    plot.margin = margin(1, 1, 1, 1, "cm")
  )

print(p_publication)

# To save the plot:
ggsave("publication.png", plot = p_publication, width = 12, height = 8, dpi = 300)

ver = data_table_result[, .(round = max(round)), by = agent_label]
ver = data_table_result[agent_label == "Agent 1",]
memo = data_table_result
# Get initial data (round 0)
initial_data <- data_table_result[round == 0, .(agent_id, agent_label, belief)]

# Create a mapping between agent_id and a simpler numeric ID
agent_mapping <- merge(network_structure, initial_data,
                       by.x = "source", by.y = "agent_id")

agent_mapping[, from := as.numeric(sub("Agent ", "", agent_label))]

uuid_to_number <- agent_mapping[, .(uuid = source, number = from)]

agent_mapping[uuid_to_number, to := i.number, on = .(target = uuid)]

# Update network_structure with numeric IDs
network_structure_numeric <- agent_mapping[, .(from, to, value)]

# Create graph object from edge list
graph <- graph_from_data_frame(network_structure_numeric, directed = TRUE)

# Add vertex attributes
V(graph)$name <- initial_data$agent_label
V(graph)$label <- sprintf("%.3f", initial_data$belief)
V(graph)$color <- muted_palette[1:vcount(graph)]  # Assign colors from palette

edge_color <- "#4B4B4B"  # A dark gray for edges

p <- ggraph(graph, layout = "fr", niter = 1000) +
  geom_edge_arc(aes(label = round(value, 2)),
                arrow = arrow(length = unit(10, 'mm'), type = "closed", ends = "last"),
                start_cap = circle(30, 'mm'),
                end_cap = circle(30, 'mm'),
                edge_width = 1,
                label_size = 10,
                angle_calc = 'along',
                label_dodge = unit(5, 'mm'),
                check_overlap = TRUE,
                label_colour = edge_color,
                strength = 0.3,
                edge_colour = edge_color) +
  geom_node_point(aes(color = name), size = 65) +
  geom_node_text(aes(label = label), size = 7, color = "white", lineheight = 0.8) +
  scale_color_manual(values = setNames(muted_palette[1:vcount(graph)], V(graph)$name)) +
  theme_graph(base_family = "serif") +
  labs(title = "Agent Influence Network",
       subtitle = "Node color: Agent, Edge labels: Influence values",
       color = "Agent") +
  theme(
    legend.position = "right",
    legend.box = "vertical",
    legend.margin = margin(6, 6, 6, 6),
    legend.key.size = unit(1, "lines"),
    legend.title = element_text(size = 20, face = "bold"),
    legend.text = element_text(size = 16),
    plot.title = element_text(size = 36, face = "bold", hjust = 0.5),
    plot.subtitle = element_text(size = 24, hjust = 0.5, face = "italic"),
    plot.background = element_rect(fill = "white", color = NA),
    panel.background = element_rect(fill = "white", color = NA)
  ) +
  guides(
    color = guide_legend(override.aes = list(size = 10))
  ) +
  coord_fixed(clip = "off") +
  scale_x_continuous(expand = expansion(mult = 0.2)) +
  scale_y_continuous(expand = expansion(mult = 0.2))

ggsave("agent_influence_network.png", p, width = 20, height = 16, dpi = 300)


# ver <- data_table_result[20 <= round & round <= 25, ]
#
# melted_data <- melt(data_table_result[20 <= round & round <= 25, ],
#                     id.vars = c("agent_label", "round"),
#                     measure.vars = c("belief", "public_belief", "is_speaking"),
#                     variable.name = "metric",
#                     value.name = "value")
#
# ver2 <- dcast(melted_data, agent_label + round ~ metric, value.var = "value")
#
# ver3 <- data_table_result[22 <= round & round <= 25, .(
#   agent_label, round, belief, public_belief, is_speaking
# )]
