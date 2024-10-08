package com.conpinion.rules;

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

sealed class CommandResult {
    data object Continue : CommandResult()
    data object Stop : CommandResult()
}

abstract class Rule(
        val description: String,
        val isEnabled: Boolean = true // Feature 1: Conditional Execution for Rules
) {
    private val mutex = Mutex()
    private var cachedResult: CommandResult? = null
    var state: RuleState = RuleState.NotEvaluated // Feature 5: Rule State Management
    val dependencies = mutableListOf<Rule>() // Dependency management

    abstract suspend fun evaluateRule(context: MutableMap<String, Any>): CommandResult

    suspend fun evaluate(context: MutableMap<String, Any>): CommandResult = mutex.withLock {
        if (!isEnabled) {
            state = RuleState.Skipped
            return CommandResult.Continue
        }
        cachedResult?.let { return it }
        val newResult = evaluateRule(context)
        cachedResult = newResult
        state = if (newResult is CommandResult.Continue) RuleState.Passed else RuleState.Failed
        return newResult
    }

    open fun resetCache() {
        cachedResult = null
        state = RuleState.NotEvaluated
    }
}

enum class RuleState { // Feature 5: Rule State Management
    NotEvaluated,
    Passed,
    Failed,
    Skipped
}

class SimpleRule(
        description: String,
        isEnabled: Boolean = true,
        private val condition: suspend (MutableMap<String, Any>) -> Boolean,
private val command: suspend (MutableMap<String, Any>) -> CommandResult
) : Rule(description, isEnabled) {
    override suspend fun evaluateRule(context: MutableMap<String, Any>): CommandResult =
    if (condition(context)) {
        command(context).also { state = RuleState.Passed }
    } else {
        state = RuleState.Failed
        CommandResult.Stop
    }
}

class CombinedCondition(
        private val conditions: List<suspend (MutableMap<String, Any>) -> Boolean>,
private val operator: LogicalOperator
) {
suspend fun evaluate(context: MutableMap<String, Any>): Boolean {
    return when (operator) {
        LogicalOperator.AND -> conditions.all { it(context) }
        LogicalOperator.OR -> conditions.any { it(context) }
        LogicalOperator.NOT -> conditions.size == 1 && !conditions[0](context)
    }
}
}

enum class LogicalOperator {
    AND, OR, NOT
}

class CombinedRule(
        description: String,
        isEnabled: Boolean = true,
        private val combinedCondition: CombinedCondition,
        private val command: suspend (MutableMap<String, Any>) -> CommandResult
) : Rule(description, isEnabled) {
    override suspend fun evaluateRule(context: MutableMap<String, Any>): CommandResult =
    if (combinedCondition.evaluate(context)) {
        command(context).also { state = RuleState.Passed }
    } else {
        state = RuleState.Failed
        CommandResult.Stop
    }
}

class RuleEngine {
    private val rules = ConcurrentHashMap.newKeySet<Rule>()

    fun addRule(rule: Rule) {
        rules += rule
    }

    suspend fun evaluate(context: MutableMap<String, Any>): Boolean = coroutineScope {
        val sortedRules = topologicalSort(rules.toList())
        sortedRules.map { rule ->
                async { rule.evaluate(context) }
        }.awaitAll().all { it !is CommandResult.Stop }
    }

    fun resetAllCaches() = rules.forEach { it.resetCache() }

    fun exportToPlantUML() {
        val diagram = buildString {
            append("@startuml\n")
            append("title Rule Engine Activity Diagram\n")
            append("start\n")

            rules.forEachIndexed { index, rule ->
                    append(":Evaluate Rule ${index + 1} (${rule.description}) [State: ${rule.state}];\n") // Feature 5: Include Rule State in Diagram
                if (rule.isEnabled) {
                    append("if (Condition Met?) then (yes)\n")
                    append("  :Execute Command ${index + 1};\n")
                    if (index < rules.size - 1) {
                        append("  :Continue to next rule;\n")
                    } else {
                        append("  stop\n")
                    }
                    append("else (no)\n")
                } else {
                    append(":Rule ${index + 1} Skipped;\n")
                }
            }

            append("stop\n")
            append("@enduml\n")
        }
        println(diagram)
    }

    fun printRuleStates() { // Added function to print rule states
        rules.forEach { rule ->
                println("Rule: ${rule.description}, State: ${rule.state}")
        }
    }

    private fun topologicalSort(rules: List<Rule>): List<Rule> {
        val visited = mutableSetOf<Rule>()
        val sorted = mutableListOf<Rule>()

        fun visit(rule: Rule) {
            if (!visited.contains(rule)) {
                visited.add(rule)
                rule.dependencies.forEach { visit(it) }
                sorted.add(rule)
            }
        }

        rules.forEach { visit(it) }
        return sorted
    }
}

fun ruleEngine(block: RuleEngineBuilder.() -> Unit): RuleEngine {
    return RuleEngineBuilder().apply(block).build()
}

class RuleEngineBuilder {
    private val rules = mutableListOf<Rule>()

    fun simpleRule(description: String, isEnabled: Boolean = true, condition: suspend (MutableMap<String, Any>) -> Boolean, command: suspend (MutableMap<String, Any>) -> CommandResult, dependsOn: List<Rule> = emptyList()): Rule {
        val rule = SimpleRule(description, isEnabled, condition, command)
        dependsOn.forEach { rule.dependencies.add(it) }
        rules.add(rule)
        return rule
    }

    fun combinedRule(description: String, isEnabled: Boolean = true, combinedCondition: CombinedCondition, command: suspend (MutableMap<String, Any>) -> CommandResult, dependsOn: List<Rule> = emptyList()): Rule {
        val rule = CombinedRule(description, isEnabled, combinedCondition, command)
        dependsOn.forEach { rule.dependencies.add(it) }
        rules.add(rule)
        return rule
    }

    fun combinedConditionWithRules(conditions: List<Rule>, operator: LogicalOperator): CombinedCondition {
        val conditionEvaluators: List<suspend (MutableMap<String, Any>) -> Boolean> = conditions.map { rule ->
        { _ -> rule.state == RuleState.Passed }
        }
        return CombinedCondition(conditionEvaluators, operator)
    }

    fun build(): RuleEngine {
        return RuleEngine().apply {
            rules.forEach { addRule(it) }
        }
    }
}

fun MutableMap<String, Any>.getInt(key: String, defaultValue: Int = 0): Int {
    return this[key] as? Int ?: defaultValue
}

fun MutableMap<String, Any>.getBoolean(key: String, defaultValue: Boolean = false): Boolean {
    return this[key] as? Boolean ?: defaultValue
}

fun main() = runBlocking {
    val engine = ruleEngine {
        // Regel 1: Mindestalter
        val rule1 = simpleRule(
                description = "Der Wert von 'age' muss größer als 18 sein",
                isEnabled = true,
                condition = { context -> context.getInt("age") >= 18 },
                command = { context ->
                        context["ageVerified"] = true
                        println("Regel 1: Alter ist größer oder gleich 18.")
                        CommandResult.Continue
                }
        )

        // Regel 2: Lebenslauf hochgeladen
        val rule2 = simpleRule(
                description = "Lebenslauf muss hochgeladen sein",
                isEnabled = true,
                condition = { context -> context["resumeUploaded"] as? Boolean == true },
                command = { _ ->
                        println("Regel 2: Lebenslauf wurde hochgeladen.")
                        CommandResult.Continue
                },
                dependsOn = listOf(rule1) // Regel 2 hängt von Regel 1 ab
        )

        // Regel 3: Bildungsabschluss oder Berufserfahrung
        val combinedCondition = combinedConditionWithRules(
                conditions = listOf(rule1, rule2),
                operator = LogicalOperator.OR
        )

        val rule3 = combinedRule(
                description = "Bewerber muss entweder Bildungsabschluss oder Berufserfahrung haben",
                isEnabled = true,
                combinedCondition = combinedCondition,
                command = { _ ->
                        println("Regel 3: Qualifikationen wurden erfüllt.")
                        CommandResult.Continue
                },
                dependsOn = listOf(rule2) // Regel 3 hängt von Regel 2 ab
        )

        // Regel 4: Referenzen prüfen
        val rule4 = simpleRule(
                description = "Mindestens zwei berufliche Referenzen müssen angegeben sein",
                isEnabled = true,
                condition = { context -> (context["references"] as? List<*>)?.size ?: 0 >= 2 },
        command = { _ ->
                println("Regel 4: Es wurden mindestens zwei Referenzen angegeben.")
                CommandResult.Continue
        },
                dependsOn = listOf(rule3) // Regel 4 hängt von Regel 3 ab
        )

        // Regel 5: Bewerbung aktiv und vollständig
        val finalCombinedCondition = combinedConditionWithRules(
                conditions = listOf(rule1, rule4),
                operator = LogicalOperator.AND
        )

        combinedRule(
                description = "Bewerbung muss aktiv und vollständig sein",
                isEnabled = true,
                combinedCondition = finalCombinedCondition,
                command = { _ ->
                        println("Regel 5: Bewerbung ist aktiv und vollständig.")
                        CommandResult.Continue
                },
                dependsOn = listOf(rule4) // Regel 5 hängt von Regel 4 ab
        )
    }

    val context: MutableMap<String, Any> = mutableMapOf("age" to 18, "resumeUploaded" to true, "education" to "completed", "workExperienceYears" to 2, "references" to listOf("Ref1", "Ref2"), "applicationStatus" to "active")

    // Evaluierung der Regeln
    engine.resetAllCaches()
    val result = engine.evaluate(context)

    engine.printRuleStates() // Drucken der Regelzustände nach der Evaluierung

    println("Alle Regeln erfüllt: $result")
    println("Aktualisierter Kontext: $context") // Den aktualisierten Kontext ausgeben, um die Änderungen zu sehen
}
