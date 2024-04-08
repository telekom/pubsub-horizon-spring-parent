package de.telekom.eni.pandora.horizon.cache.serdes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.StreamSerializer;
import de.telekom.eni.pandora.horizon.utils.ObjectMapperFactory;

import java.io.IOException;

public class GenericJacksonSerdes implements StreamSerializer<Object> {

    private final ObjectMapper objectMapper = ObjectMapperFactory.create();

    @Override
    public void write(ObjectDataOutput out, Object oo) throws IOException {
        out.writeUTF(oo.getClass().getName());
        out.writeByteArray(objectMapper.writeValueAsBytes(oo));
    }

    @Override
    public Object read(ObjectDataInput in) throws IOException {
        String clazz = in.readUTF();
        try {
            return objectMapper.readValue(in.readByteArray(), Class.forName(clazz));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getTypeId() {
        return 1;
    }

    @Override
    public void destroy() {

    }
}