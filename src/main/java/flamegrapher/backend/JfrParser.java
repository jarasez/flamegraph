package flamegrapher.backend;

import static org.openjdk.jmc.flightrecorder.JfrAttributes.EVENT_STACKTRACE;

import java.io.File;
import java.io.IOException;
import java.util.Stack;

import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCMethod;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.item.*;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

import flamegrapher.backend.JsonOutputWriter.StackFrame;

public class JfrParser {

    public StackFrame toJson(File jfr, String... eventTypes) throws IOException, CouldNotLoadRecordingException {
        IItemCollection filtered = JfrLoaderToolkit.loadEvents(jfr)
            .apply(ItemFilters.type(eventTypes));

        JsonOutputWriter writer = new JsonOutputWriter();
        filtered.forEach(events -> {
            IMemberAccessor<IMCStackTrace, IItem> accessor = events.getType()
                .getAccessor(EVENT_STACKTRACE.getKey());

            for (IItem item : events) {
                Stack<String> stack = new Stack<>();
                IMCStackTrace stackTrace = accessor.getMember(item);
                if (stackTrace == null || stackTrace.getFrames() == null) {

                    continue;
                }
                stackTrace.getFrames()
                    .forEach(frame -> {
                        stack.push(getFrameName(frame));
                    });
                Long value = getValue(events, item, eventTypes);
                writer.processEvent(stack, value);
            }
        });

        return writer.getStackFrame();
    }

    /**
     * Returns the value according to the event type. For most event types, we
     * will only take into account the occurrence in itself. For example, Method
     * CPU sample will only return 1, i.e. one occurence of that given stack
     * trace while sampling. But for locks, we will look into the total time
     * spent waiting for that lock. For allocation, we will look at the total
     * amount of memory allocated. And so on.
     *
     * @param events
     */
    private Long getValue(IItemIterable events, IItem item, String[] eventTypes) {

        for (String eventType : eventTypes) {
            if (JdkTypeIDs.MONITOR_ENTER.equals(eventType)) {
                return getOrCalculateDuration(item, events);

            } else if (JdkTypeIDs.ALLOC_INSIDE_TLAB.equals(eventType)
                    || JdkTypeIDs.ALLOC_OUTSIDE_TLAB.equals(eventType)) {
                IMemberAccessor<IQuantity, IItem> accessor = events.getType()
                                                                   .getAccessor(JdkAttributes.ALLOCATION_SIZE.getKey());
                IQuantity allocationSize = accessor.getMember(item);
                return allocationSize.clampedLongValueIn(UnitLookup.BYTE);
            }
        }
        // For all other event types, simply return 1.
        return 1L;
    }

    /**
     * Calculates duration using start and end times when duration accessor is missing.
     *
     * @param item
     * @param events
     * @return
     */
    private Long getOrCalculateDuration(IItem item, IItemIterable events) {
        Long duration;

        final IMemberAccessor<IQuantity, IItem> durationAccessor = events.getType()
                                                                         .getAccessor(JfrAttributes.DURATION.getKey());

        if (durationAccessor != null) {
            duration = durationAccessor.getMember(item).clampedLongValueIn(UnitLookup.MILLISECOND);

        } else {
            final IMemberAccessor<IQuantity, IItem> startAccessor = events.getType()
                                                                          .getAccessor(JfrAttributes.START_TIME.getKey());
            final IMemberAccessor<IQuantity, IItem> endAccessor = events.getType()
                                                                          .getAccessor(JfrAttributes.END_TIME.getKey());

            if (startAccessor != null && endAccessor != null) {
                duration = endAccessor.getMember(item).subtract(startAccessor.getMember(item))
                                                      .clampedLongValueIn(UnitLookup.MILLISECOND);
            } else {
                throw new ParserException("Event duration is unknown!");
            }
        }

        return duration;
    }

    private String getFrameName(IMCFrame frame) {
        // TODO: Make it a configuration parameter
        boolean ignoreLineNumbers = false;

        StringBuilder methodBuilder = new StringBuilder();
        IMCMethod method = frame.getMethod();
        methodBuilder.append(method.getType()
                                   .getFullName())
                     .append("#")
                     .append(method.getMethodName());

        if (!ignoreLineNumbers) {
            methodBuilder.append(":");
            methodBuilder.append(frame.getFrameLineNumber());
        }
        return methodBuilder.toString();
    }

}
