package com.neuron.ai.core.conversation

import kotlinx.serialization.Serializable

/**
 * A single interactive multiple-choice question.
 */
@Serializable
data class InteractiveQuestion(
    val question: String,
    val options: List<String> = emptyList(),
    val allowCustom: Boolean = true,
    val header: String? = null
)

/**
 * A set of one or more interactive questions to present to the user
 * as an interactive card (with "1 of N" paging, numbered options, and custom input).
 */
@Serializable
data class QuestionCardPayload(
    val id: String = "qc-${System.currentTimeMillis()}",
    val title: String? = null,
    val questions: List<InteractiveQuestion> = emptyList()
) {
    val totalCount: Int get() = questions.size
}
