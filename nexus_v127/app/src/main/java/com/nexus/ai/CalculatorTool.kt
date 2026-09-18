package com.nexus.ai

import com.nexus.ai.security.PermissionKind

class CalculatorTool : NexusTool {
    override val definition = NexusToolDefinition(
        name = "calculator",
        description = "Calcula expressões matemáticas simples com números, + - * / e parênteses.",
        parametersJson = """{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"],"additionalProperties":false}""",
        readOnly = true,
        permission = PermissionKind.TOOL_EXECUTION
    )

    override suspend fun execute(argumentsJson: String): ToolResult {
        val expression = Regex(""""expression"\s*:\s*"([^"]+)"""").find(argumentsJson)?.groupValues?.get(1)
            ?: return ToolResult("calculator", "calculator", "Expressão ausente.", false)
        return try {
            val value = SafeMath.evaluate(expression)
            ToolResult("calculator", "calculator", value.toString(), true)
        } catch (e: Exception) {
            ToolResult("calculator", "calculator", "Expressão inválida.", false)
        }
    }
}

private object SafeMath {
    fun evaluate(s: String): Double {
        val p = Parser(s.replace(" ", ""))
        val result = p.expr()
        if (!p.end()) error("trailing")
        return result
    }
    private class Parser(private val s: String) {
        var i = 0
        fun expr(): Double {
            var x = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val y = term(); x = if (op == '+') x+y else x-y
            }
            return x
        }
        fun term(): Double {
            var x = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]; val y = factor(); if (op == '/' && y == 0.0) error("zero")
                x = if (op == '*') x*y else x/y
            }
            return x
        }
        fun factor(): Double {
            if (i < s.length && s[i] == '(') { i++; val x=expr(); if (i>=s.length || s[i++]!=')') error(")") ; return x }
            val start=i
            if (i<s.length && (s[i]=='+' || s[i]=='-')) i++
            while (i<s.length && (s[i].isDigit() || s[i]=='.')) i++
            if (start==i) error("number")
            return s.substring(start,i).toDouble()
        }
        fun end() = i == s.length
    }
}
