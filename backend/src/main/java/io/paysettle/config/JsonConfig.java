package io.paysettle.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serialises Postgres array columns (e.g. fraud_reasons TEXT[]) returned by JdbcClient row maps as JSON arrays. */
@Configuration
public class JsonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer sqlArrays() {
        SimpleModule module = new SimpleModule("sql-arrays");
        module.addSerializer(Array.class, new JsonSerializer<>() {
            @Override
            public void serialize(Array value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                try {
                    Object[] items = (Object[]) value.getArray();
                    gen.writeStartArray();
                    for (Object item : items) {
                        gen.writeObject(item);
                    }
                    gen.writeEndArray();
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            }
        });
        return builder -> builder.modulesToInstall(module);
    }
}
