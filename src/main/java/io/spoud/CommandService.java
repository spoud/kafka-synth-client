package io.spoud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CommandService {

    /**
     * Command to adjust the payload size.
     * @param newSize the new payload size
     */
    public record AdjustPayloadSizeCommand(int newSize) {}

    /**
     * Wrapper record around all possible commands.
     * @param adjustPayloadSize
     */
    public record Command(AdjustPayloadSizeCommand adjustPayloadSize) {}

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final KafkaSynthClient kafkaSynthClient;

    public CommandService(KafkaSynthClient kafkaSynthClient) {
        this.kafkaSynthClient = kafkaSynthClient;
    }

    /**
     * If the given string represents a valid command, it will be handled. Else, it will be ignored.
     *
     * @param string JSON-serialized command
     */
    public void maybeHandleCommand(String string) {
        try {
            var command = objectMapper.readValue(string, Command.class);
            Log.infof("Received command: %s", command);
            if (command.adjustPayloadSize != null) {
                handleAdjustPayloadSizeCommand(command.adjustPayloadSize);
            }
        } catch (JsonProcessingException e) {
            Log.warn("Failed to parse command", e);
        } catch (Exception e) {
            Log.error("Failed to handle command", e);
        }
    }

    public void issueCommand(Command command) {
        try {
            kafkaSynthClient.produceSingleMessage(objectMapper.writeValueAsString(command));
        } catch (JsonProcessingException e) {
            Log.error("Failed to serialize command", e);
        }
    }

    private void handleAdjustPayloadSizeCommand(AdjustPayloadSizeCommand command) {
        Log.infof("Received command to adjust payload size to %d", command.newSize);
        kafkaSynthClient.setPayloadSize(command.newSize);
    }
}
