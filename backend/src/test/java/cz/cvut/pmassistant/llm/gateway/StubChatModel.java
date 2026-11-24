package cz.cvut.pmassistant.llm.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** A chat model that answers with what the test gives it and remembers the prompts it was asked. */
final class StubChatModel implements ChatModel {

	final List<Prompt> prompts = new ArrayList<>();
	private final Function<Prompt, ChatResponse> answer;

	StubChatModel(Function<Prompt, ChatResponse> answer) {
		this.answer = answer;
	}

	static StubChatModel answering(ChatResponse response) {
		return new StubChatModel(prompt -> response);
	}

	static StubChatModel failing(RuntimeException failure) {
		return new StubChatModel(prompt -> {
			throw failure;
		});
	}

	static ChatResponse text(String text, String finishReason, int prompt, int completion) {
		return new ChatResponse(
				List.of(new Generation(new AssistantMessage(text),
						ChatGenerationMetadata.builder().finishReason(finishReason).build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(prompt, completion)).build());
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		prompts.add(prompt);
		return answer.apply(prompt);
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return ToolCallingChatOptions.builder().model("stub-model").build();
	}

	Prompt lastPrompt() {
		return prompts.get(prompts.size() - 1);
	}
}
