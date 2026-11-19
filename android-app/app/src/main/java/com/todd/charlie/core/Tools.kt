package com.todd.charlie.core

import org.json.JSONArray
import org.json.JSONObject

class Tool(val name: String, val description: String, val schema: JSONObject, val handler: (JSONObject) -> JSONObject)

/** Named tools only. There is no "run a command" tool. */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun add(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList(), handler: (JSONObject) -> JSONObject) {
        val schema = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        tools[name] = Tool(name, description, schema, handler)
    }

    fun names(): List<String> = tools.keys.toList()

    fun listJson(): JSONArray {
        val arr = JSONArray()
        for (t in tools.values) arr.put(JSONObject().put("name", t.name).put("description", t.description).put("inputSchema", t.schema))
        return arr
    }

    /** MCP tools/call result: {content:[{type:text,text}], isError?}. */
    fun call(name: String, args: JSONObject?): JSONObject {
        val t = tools[name] ?: return mcpText("unknown tool", true)
        val payload = try {
            t.handler(args ?: JSONObject())
        } catch (e: Exception) {
            JSONObject().put("error", "tool failed: " + e.javaClass.simpleName + (e.message?.let { ": " + it.take(200) } ?: ""))
        }
        return mcpText(payload.toString(), payload.has("error"))
    }

    companion object {
        fun mcpText(text: String, isError: Boolean): JSONObject {
            val o = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            if (isError) o.put("isError", true)
            return o
        }

        fun err(msg: String): JSONObject = JSONObject().put("error", msg)
    }
}

object Props {
    fun of(vararg p: Pair<String, JSONObject>): JSONObject = JSONObject().apply { for ((k, v) in p) put(k, v) }
    fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    fun choice(desc: String, vararg values: String) = JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))
}
