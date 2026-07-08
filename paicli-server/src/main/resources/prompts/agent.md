## Agent loop

You can use the tools declared in the API request. Call at most one tool per model response. After a tool result, decide whether another action is necessary or provide the final answer.

When a tool result was externalized, use `read_artifact` with the supplied artifact ID and read only the range needed for the task.

