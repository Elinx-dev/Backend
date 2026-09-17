package in.gov.slate.common;

import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Query results are returned as plain maps, so PostgreSQL driver types reach the
 * JSON writer directly. Arrays are rendered as JSON arrays, jsonb columns as
 * embedded JSON and other driver objects (citext, enums) as their text value.
 */
@Configuration
public class JdbcJsonConfig {

    @Bean
    public SimpleModule postgresTypesModule() {
        SimpleModule module = new SimpleModule("slate-postgres-types");
        module.addSerializer(Array.class, new JsonSerializer<Array>() {
            @Override
            public void serialize(Array value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                try {
                    Object elements = value.getArray();
                    gen.writeObject(elements instanceof Object[] array ? Arrays.asList(array) : elements);
                } catch (SQLException e) {
                    throw new IOException("Unable to read SQL array", e);
                }
            }
        });
        module.addSerializer(PGobject.class, new JsonSerializer<PGobject>() {
            @Override
            public void serialize(PGobject value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                String raw = value.getValue();
                if (raw == null) {
                    gen.writeNull();
                } else if ("json".equals(value.getType()) || "jsonb".equals(value.getType())) {
                    gen.writeRawValue(raw);
                } else {
                    gen.writeString(raw);
                }
            }
        });
        return module;
    }
}
