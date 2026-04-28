library(pacman)
pacman::p_load(
  RPostgres, data.table, ggplot2, ggthemes, scales, plotly, viridis, hrbrthemes,
  igraph, ggraph, dplyr, svglite, tidygraph, stringr
)

# Create a new DBI connection
conn <- dbConnect(RPostgres::Postgres(),
                  dbname = "promueva_new",
                  host = "localhost",
                  port = 5433,
                  user = "postgres",
                  password = "postgres")

network <- "
SELECT id FROM
	networks
WHERE
	run_id = (SELECT id FROM runs ORDER BY id DESC LIMIT 1)
ORDER BY final_round LIMIT 1;
"
q0_res <- as.data.table(dbGetQuery(conn, network))
current_network <- ""
current_network <- q0_res$id

speaking_agents <- str_glue("
SELECT * FROM agent_states_speaking WHERE agent_id in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

silent_agents <-  str_glue("
SELECT * FROM agent_states_silent WHERE agent_id in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

neighbors_initial <- str_glue("
SELECT * FROM neighbors WHERE round = 0
AND source IN (
    SELECT id FROM agents WHERE network_id = '{current_network}'
);
")

neighbors_final <- str_glue("
SELECT n.* FROM neighbors n
WHERE n.round = (SELECT final_round FROM networks WHERE id = CAST('{current_network}' AS uuid))
AND n.source IN (
    SELECT id FROM agents WHERE network_id = '{current_network}'
);
")

agents <- str_glue("
SELECT * FROM agents WHERE network_id = '{current_network}';
")
# Base 01920c94-853a-7000-9764-060412ab4ccb
# extended to 3 01920dcc-ab76-7000-921f-cbc78d33b8f5
# extended to 3 confidence 01920e15-2cc5-7000-a5a5-3e82c904f172
# Perpetual silence

# 01920db8-940e-7000-a5b8-7fa486907dbe

speaking_agents <- as.data.table(dbGetQuery(conn, speaking_agents))
silent_agents <- as.data.table(dbGetQuery(conn, silent_agents))
neighbors_initial <- as.data.table(dbGetQuery(conn, neighbors_initial))
neighbors_final   <- as.data.table(dbGetQuery(conn, neighbors_final))
agents <- as.data.table(dbGetQuery(conn, agents))
dbDisconnect(conn)

# Use initial topology for static analysis; use neighbors_final for co-evolution comparisons.
# For scripts that previously expected a single `neighbors` object, alias to initial:
neighbors <- neighbors_initial

speaking_agents[, is_speaking := TRUE]
silent_agents[, is_speaking := FALSE]

agent_rounds <- rbind(speaking_agents, silent_agents)
agent_rounds[, state_data := NULL]

data_table_result <- merge(agent_rounds, agents[, .(id, tolerance_radius, name)], by.x = "agent_id", by.y = "id")
setnames(data_table_result, "name", "agent_label")

# Create a dataset with consecutive points for segments
data_segments <- data_table_result[order(agent_label, round)]
data_segments[, next_round := shift(round, type="lead"), by=agent_label]
data_segments[, next_belief := shift(belief, type="lead"), by=agent_label]

ggplot() +
  geom_segment(data = data_segments[!is.na(next_round)],
               aes(x = round, y = belief,
                   xend = next_round, yend = next_belief,
                   color = agent_label,
                   linetype = is_speaking)) +
  geom_point(data = data_table_result,
             aes(x = round, y = belief, color = agent_label), size = 2) +
  scale_y_continuous(limits = c(0, 1)) +
  scale_linetype_manual(values = c("FALSE" = "dashed", "TRUE" = "solid")) +
  labs(title = "Belief Evolution Over Time",
       x = "Round",
       y = "Belief",
       color = "Agent",
       linetype = "Is Speaking") +
  theme_minimal()

max(data_table_result$round)

# Find the most recent non-NA beliefs and public beliefs
recent_beliefs <- data_table_result[, .SD[which.max(round)], by = agent_id]

# Merge max rounds with recent beliefs
recent_beliefs <- recent_beliefs[round != max(data_table_result$round)]
recent_beliefs[, round := max(data_table_result$round)]

# Add the new rows to the original data table
data_table_result <- rbindlist(list(data_table_result, recent_beliefs), use.names = TRUE, fill = TRUE)

# Sort the data table
data_table_result$is_speaking <- as.factor(data_table_result$is_speaking)
#data_table_result <- data_table_result[order(agent_id)]
data_table_result <- data_table_result[order(as.numeric(gsub("Agent", "", agent_label)), round)]

# Only use when cloning
# cloned <- data_table_result[round==1]
#
# for (i in 2:10) {
#   cloned[, round := i]
#   data_table_result <- rbind(data_table_result, cloned)
# }

# Filter data to reduce overplotting
# <- data_table_result[round <= 5000 & round %% 50 == 0]
max_round_val <- 200
rounds <- min(max_round_val, max(data_table_result$round))

insert_round <- function(dt, target_round) {
  # Get the most recent round for each agent up to the target round
  most_recent <- dt[round <= target_round, .SD[which.max(round)], by = agent_id]

  # Identify agents missing the target round
  missing_agents <- most_recent[!agent_id %in% dt[round == target_round, agent_id]]

  # If there are missing agents, create new rows for them
  if (nrow(missing_agents) > 0) {
    new_rows <- copy(missing_agents)
    new_rows[, round := target_round]

    # Combine original data with new rows
    dt <- rbindlist(list(dt, new_rows), use.names = TRUE, fill = TRUE)

    # Sort the data by agent_id and round
    setorder(dt, agent_id, round)
  }

  return(dt)
}

# for (i in 1:rounds) {
#   data_table_result <- insert_round(data_table_result[round <= i], i)
# }

data_table_result <- insert_round(data_table_result, max_round_val)
filtered_data <- data_table_result[round <= rounds]
#filtered_data <- data_table_result[round <= rounds & round %% 5 == 0]


# Prepare the data
melted_data <- melt(filtered_data,
                    id.vars = c("round", "agent_id", "agent_label", "is_speaking", "tolerance_radius"),
                    measure.vars = c("belief", "public_belief"),
                    variable.name = "belief_type", value.name = "value")

# Rename belief types for clarity
melted_data$belief_type <- factor(melted_data$belief_type,
                                  levels = c("belief", "public_belief"),
                                  labels = c("Opinion", "Public Opinion"))

# Extract agent numbers and create a numeric version for sorting
melted_data$agent_number <- as.numeric(gsub("Agent", "", melted_data$agent_label))

# Order the data by agent number and round
melted_data <- melted_data[order(melted_data$agent_number, melted_data$round), ]

# Create ordered levels for agent_label
ordered_agents <- paste0("Agent", sort(unique(melted_data$agent_number)))

# Create ordered factor for agent_label
melted_data$agent_label <- factor(melted_data$agent_label, levels = ordered_agents)

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

max_tol_radius <- max(data_table_result[round==0]$tolerance_radius)

# Ensure the palette has enough colors
if(length(muted_palette) < length(ordered_agents)) {
  muted_palette <- colorRampPalette(muted_palette)(length(ordered_agents))
}

# Create the plot
has_public_belief <- !anyNA(data_table_result$public_belief)

melted_data <- melted_data %>%
  mutate(shape_group = case_when(
    belief_type == "Public Opinion" ~ "Public",
    is_speaking == TRUE ~ "Non-silent",
    TRUE ~ "Silent"
  ))

# Create the base plot
p_publication <- ggplot(melted_data,
                        aes(x = round, y = value,
                            group = interaction(agent_label, belief_type),
                            color = agent_label,
                            fill = agent_label,
                            linetype = belief_type,
                            shape = shape_group)) +
  # Add semi-transparent ribbons for tolerance radius
  geom_ribbon(data = subset(melted_data, belief_type == "Opinion"),
              aes(ymin = pmax(value - tolerance_radius, 0),
                  ymax = pmin(value + tolerance_radius, 1)),
              alpha = 0.2,
              color = NA) +
  geom_line(size = 1, alpha = 0.8) +
  geom_point(size = 3, alpha = 0.8) +
  labs(x = "Time",
       y = "Opinion",
       color = "Agent",
       shape = "Silent Status") +
  scale_color_manual(values = setNames(muted_palette[seq_along(ordered_agents)], ordered_agents),
                     breaks = ordered_agents) +
  scale_fill_manual(values = setNames(muted_palette[seq_along(ordered_agents)], ordered_agents),
                    breaks = ordered_agents,
                    guide = "none") +
  scale_shape_manual(values = c("Public" = 16, "Non-silent" = 16, "Silent" = 17),
                     labels = c("Non-silent" = "Non-silent", "Silent" = "Silent"),
                     breaks = c("Non-silent", "Silent")) +
  scale_x_continuous(breaks = seq(0, max(melted_data$round), by = 5),
                     limits = c(-0.3, max(melted_data$round) + 0.3), expand = c(0, 0)) +
  scale_y_continuous(limits = c(-0.02, 1.02), breaks = seq(0, 1, 0.1), expand = c(0.01, 0)) +
  theme_minimal(base_size = 20) +
  theme(
    plot.title = element_text(size = 24, face = "bold", hjust = 0.5),
    plot.subtitle = element_text(size = 24, hjust = 0.5),
    axis.title = element_text(size = 24, face = "bold"),
    axis.text = element_text(size = 24),
    axis.ticks = element_line(color = "black", size = 0.5),
    axis.ticks.length = unit(0.25, "cm"),
    legend.title = element_text(size = 24, face = "bold"),
    legend.text = element_text(size = 24),
    legend.position = "right",
    legend.box = "vertical",
    panel.grid.major = element_blank(),
    panel.grid.minor = element_blank(),
    legend.key.size = unit(0.5, "cm"),  # New: Adjust legend key size
    legend.key.width = unit(1.5, "cm"),  # New: Adjust legend key width
    panel.border = element_rect(color = "black", fill = NA, size = 1),
    plot.margin = margin(1, 1, 1, 1, "cm")
  )

# Add linetype scale and legend only if public opinion exists
if (has_public_belief) {
  p_publication <- p_publication +
    scale_linetype_manual(values = c("Opinion" = "solid", "Public Opinion" = "dashed"),
                          guide = guide_legend(override.aes = list(linetype = c("solid", "dashed")))) +
    labs(linetype = "Plot")
} else {
  # If no public opinion, remove linetype from aesthetics
  p_publication <- p_publication +
    aes(linetype = NULL)
}

p_publication <- p_publication +
  guides(linetype = guide_legend(override.aes = list(linewidth = 1)))

print(p_publication)

# ggsave("publication.svg", plot = p_publication, width = 15, height = 10, dpi = 300)
ggsave("testing.svg", plot = p_publication, width = 16, height = 10, units = "cm", dpi = 300)


# p_publication <- ggplot(melted_data,
#                         aes(x = round, y = value,
#                             group = interaction(agent_label, belief_type),
#                             color = agent_label,
#                             linetype = belief_type,
#                             shape = shape_group)) +
#   # Add error bars for belief uncertainty
#   geom_errorbar(data = subset(melted_data, belief_type == "Belief"),
#                 aes(ymin = pmax(value - tolerance_radius, 0),
#                     ymax = pmin(value + tolerance_radius, 1)),
#                 width = 0.2, size = 0.8, alpha = 0.8) +
#   geom_line(size = 1, alpha = 0.8) +
#   geom_point(size = 3, alpha = 0.8) +
#   labs(x = "Round",
#        y = "Belief",
#        color = "Agent",
#        shape = "Speaking Status") +
#   scale_color_manual(values = setNames(muted_palette[seq_along(ordered_agents)], ordered_agents),
#                      breaks = ordered_agents) +
#   scale_shape_manual(values = c("Public" = 16, "Speaking" = 16, "Silent" = 17),
#                      labels = c("Speaking" = "Speaking", "Silent" = "Silent"),
#                      breaks = c("Speaking", "Silent")) +
#   scale_x_continuous(breaks = seq(0, max(melted_data$round), by = 5),
#                      limits = c(-0.3, max(melted_data$round) + 0.3), expand = c(0, 0)) +
#   scale_y_continuous(limits = c(-0.02, 1.02), breaks = seq(0, 1, 0.1), expand = c(0.01, 0)) +
#   theme_minimal(base_size = 20) +
#   theme(
#     plot.title = element_text(size = 24, face = "bold", hjust = 0.5),
#     plot.subtitle = element_text(size = 24, hjust = 0.5),
#     axis.title = element_text(size = 24, face = "bold"),
#     axis.text = element_text(size = 24),
#     axis.ticks = element_line(color = "black", size = 0.5),
#     axis.ticks.length = unit(0.25, "cm"),
#     legend.title = element_text(size = 24, face = "bold"),
#     legend.text = element_text(size = 24),
#     legend.position = "right",
#     legend.box = "vertical",
#     panel.grid.major = element_blank(),
#     panel.grid.minor = element_blank(),
#     panel.border = element_rect(color = "black", fill = NA, size = 1),
#     plot.margin = margin(1, 1, 1, 1, "cm")
#   )
#
# # Add linetype scale and legend only if public belief exists
# if (has_public_belief) {
#   p_publication <- p_publication +
#     scale_linetype_manual(values = c("Belief" = "solid", "Public Belief" = "dotted"),
#                           guide = guide_legend(override.aes = list(linetype = c("solid", "dotted")))) +
#     labs(linetype = "Measure")
# } else {
#   # If no public belief, remove linetype from aesthetics
#   p_publication <- p_publication +
#     aes(linetype = NULL)
# }
#
# print(p_publication)
# ggsave("publication3.svg", plot = p_publication, width = 10, height = 6.5, units = "in", dpi = 300)



# other stuff



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


# p_thickness <- ggplot(melted_data,
#                       aes(x = round, y = value,
#                           group = interaction(agent_label, belief_type),
#                           color = agent_label)) +
#   geom_line(aes(size = belief_type), alpha = 0.8) +
#   geom_point(aes(shape = is_speaking), size = 4) +
#   scale_size_manual(values = c("Private Belief" = 1, "Public Belief" = 2.5)) +
#   labs(size = "Measure") +
#   theme_minimal(base_size = 20)
#
# # Option 2: Color saturation
# p_saturation <- ggplot(melted_data,
#                        aes(x = round, y = value,
#                            group = interaction(agent_label, belief_type),
#                            color = agent_label)) +
#   geom_line(aes(alpha = belief_type), size = 1.5) +
#   geom_point(aes(shape = is_speaking), size = 4) +
#   scale_alpha_manual(values = c("Private Belief" = 1, "Public Belief" = 0.4)) +
#   labs(alpha = "Measure") +
#   theme_minimal(base_size = 20)
#
# # Option 3: Combination (line type and thickness)
# p_combo <- ggplot(melted_data,
#                   aes(x = round, y = value,
#                       group = interaction(agent_label, belief_type),
#                       color = agent_label)) +
#   geom_line(aes(linetype = belief_type, size = belief_type)) +
#   geom_point(aes(shape = is_speaking), size = 4) +
#   scale_linetype_manual(values = c("Private Belief" = "solid", "Public Belief" = "dashed")) +
#   scale_size_manual(values = c("Private Belief" = 1, "Public Belief" = 1)) +
#   labs(linetype = "Measure", size = "Measure") +
#   theme_minimal(base_size = 20)
#
# # Function to add common elements to all plots
# add_common_elements <- function(p) {
#   p +
#     labs(title = "Evolution of Agent Beliefs and Speaking Status Over Rounds",
#          x = "Round", y = "Value", color = "Agent", shape = "Speaking Status") +
#     scale_color_manual(values = muted_palette) +
#     scale_shape_manual(values = c("TRUE" = 16, "FALSE" = 1),
#                        labels = c("Silent", "Speaking")) +
#     scale_x_continuous(breaks = seq(0, rounds+1, by = 5), limits = c(-1, rounds + 1), expand = c(0, 0)) +
#     scale_y_continuous(limits = c(0, 1), breaks = seq(0, 1, 0.1), expand = c(0.01, 0)) +
#     theme(
#       plot.title = element_text(size = 24, face = "bold", hjust = 0.5),
#       axis.title = element_text(size = 24, face = "bold"),
#       axis.text = element_text(size = 24),
#       legend.title = element_text(size = 24, face = "bold"),
#       legend.text = element_text(size = 24),
#       legend.position = "right",
#       legend.box = "vertical",
#       panel.grid.major = element_line(color = "grey90", size = 0.1),
#       panel.grid.minor = element_blank(),
#       plot.margin = margin(1, 1, 1, 1, "cm")
#     )
# }
#
# # Apply common elements to all plots
# p_thickness <- add_common_elements(p_thickness)
# p_saturation <- add_common_elements(p_saturation)
# p_combo <- add_common_elements(p_combo)
#
# # Print all plots
# print(p_thickness)
# print(p_saturation)
# print(p_combo)


##################################################PARETOO############################################################
# Vibrant color palette
# new_palette <- c("#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2")
# tableau_palette <- c("#006BA2", "#FF800E", "#ABABAB", "#595959", "#5F9ED1", "#C85200", "#898989")
# muted_palette <- c(
#   "#4878D0",  # Blue
#   "#EE854A",  # Orange
#   "#6ACC64",  # Green
#   "#D65F5F",  # Red
#   "#956CB4",  # Purple
#   "#8C613C",  # Brown
#   "#DC7EC0",  # Pink
#   "#45B4C2",  # Teal
#   "#CEA93E",  # Gold
#   "#7F7F7F"   # Gray
# )
#
# double_palette <- c(
#   "#BBCCEE",
#   "#222255",
#   "#CCEEFF",
#   "#225555",
#   "#CCDDAA",
#   "#225522",
#   "#EEEEBB",
#   "#666633",
#   "#FFCCCC",
#   "#663333",
#   "#E5E5E5",
#   "#686868"
# )
#
#
# pale_double_palette <- c(
#   "#BBCCEE",
#   "#CCEEFF",
#   "#CCDDAA",
#   "#EEEEBB",
#   "#FFCCCC",
#   "#E5E5E5"
# )
#
# dark_double_palette <- c(
#   "#222255",
#   "#225555",
#   "#225522",
#   "#666633",
#   "#663333",
#   "#686868"
# )