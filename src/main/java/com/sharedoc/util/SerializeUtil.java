package com.sharedoc.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Serialization utility.
 * Reserves a Java Serializable based protocol without introducing JSON dependencies.
 */
public final class SerializeUtil {
    private SerializeUtil() {
        // TODO: Add protocol versioning if message format evolves.
    }

    public static byte[] toBytes(Object object) {
        // TODO: Add null handling and checked exception strategy for final protocol.
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             ObjectOutputStream objectStream = new ObjectOutputStream(byteStream)) {
            objectStream.writeObject(object);
            return byteStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Serialization failed.", e);
        }
    }

    public static Object fromBytes(byte[] bytes) {
        // TODO: Validate message type after deserialization.
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
             ObjectInputStream objectStream = new ObjectInputStream(byteStream)) {
            return objectStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Deserialization failed.", e);
        }
    }
}
