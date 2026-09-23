package com.ticketsystem.ticket.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Binds a {@link Patch} field, keeping the three PATCH states apart:
 *
 * <ul>
 *   <li>property missing → {@link #getAbsentValue} → {@link Patch#absent()}
 *   <li>property present as JSON {@code null} → {@link #getNullValue} → {@code Patch.of(null)}
 *   <li>property present with a value → {@link #deserialize} → {@code Patch.of(value)}
 * </ul>
 *
 * <p>Jackson's own {@code Optional} support collapses the first two cases into {@code Optional.empty()},
 * which is why this deserializer exists rather than reusing it (Requirement 4.2).
 *
 * <p>{@link ContextualDeserializer} is implemented so the wrapped type argument is known at bind time;
 * the contained value is delegated to whatever deserializer that type already uses, so enums keep the
 * strict-name handling configured in {@code JacksonConfig} and text is not trimmed on the way in.
 */
public class PatchDeserializer extends StdDeserializer<Patch<?>> implements ContextualDeserializer {

    private static final long serialVersionUID = 1L;

    private final JavaType valueType;
    private final JsonDeserializer<?> valueDeserializer;

    public PatchDeserializer() {
        this(null, null);
    }

    private PatchDeserializer(JavaType valueType, JsonDeserializer<?> valueDeserializer) {
        super(Patch.class);
        this.valueType = valueType;
        this.valueDeserializer = valueDeserializer;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
            throws JsonMappingException {
        JavaType wrapperType = (property != null) ? property.getType() : context.getContextualType();
        JavaType contained = (wrapperType != null && wrapperType.containedTypeCount() > 0)
                ? wrapperType.containedType(0)
                : context.constructType(Object.class);
        return new PatchDeserializer(contained, context.findContextualValueDeserializer(contained, property));
    }

    @Override
    public Patch<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (valueDeserializer == null) {
            // Only reachable if the bare deserializer is used without contextualization.
            return Patch.of(context.readValue(parser, Object.class));
        }
        return Patch.of(valueDeserializer.deserialize(parser, context));
    }

    /** An explicit JSON null means "clear this field", which is a present state, not an absent one. */
    @Override
    public Patch<?> getNullValue(DeserializationContext context) {
        return Patch.of(null);
    }

    /** A property Jackson never saw: the client expressed no intent, so the field stays untouched. */
    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return Patch.absent();
    }

    /** Used when a record/creator parameter is missing and Jackson asks for the empty state. */
    @Override
    public Object getEmptyValue(DeserializationContext context) {
        return Patch.absent();
    }

    @Override
    public JavaType getValueType() {
        return valueType;
    }
}
