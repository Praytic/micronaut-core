/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.netty;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.UnicastProcessor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

/**
 * A helper class to store references to httpdata and related information.
 *
 * @author James Kleeh
 * @since 1.1.0
 */
@Internal
public class HttpDataReference {

    private static final Logger LOG = LoggerFactory.getLogger(HttpDataReference.class);

    final AtomicReference<UnicastProcessor> subject = new AtomicReference<>();
    final AtomicReference<StreamingFileUpload> upload = new AtomicReference<>();

    private final HttpData data;
    private final AtomicReference<RandomAccessFile> fileAccess = new AtomicReference<>();
    private final AtomicLong position = new AtomicLong(0);
    private final List<Component> components = new ArrayList<>();

    /**
     * @param data The data this class is in control of
     */
    HttpDataReference(HttpData data) {
        this.data = data;
        data.retain();
    }

    /**
     * @return The content type of the http data
     */
    public Optional<MediaType> getContentType() {
        if (data instanceof FileUpload) {
            return Optional.of(MediaType.of(((FileUpload) data).getContentType()));
        } else {
            return Optional.empty();
        }
    }

    /**
     * Adds a reference to a section of the http data. Should only
     * be called after data has been added to the underlying http data.
     *
     * @return The newly added component, or null if an error occurred
     */
    Component addComponent() throws IOException {
        Component component;
        long readable = readableBytes(data);
        long offset = position.getAndUpdate(p -> readable);
        int length = (int) (readable - offset);
        if (length == 0) {
            return null;
        }
        component = new Component(length, offset);
        components.add(component);

        if (!data.isInMemory()) {
            AtomicReference<IOException> error = new AtomicReference<>();
            fileAccess.getAndUpdate(channel -> {
                if (channel == null) {
                    try {
                        return new RandomAccessFile(data.getFile(), "r");
                    } catch (IOException e) {
                        error.set(e);
                    }
                }
                return channel;
            });
            IOException exception = error.get();
            if (exception != null) {
                throw exception;
            }
        }

        return component;
    }

    /**
     * Removes a section from the http data and updates the
     * indices of the remaining components.
     *
     * @param index The index of the component to remove.
     */
    void removeComponent(int index) {
        Component component = components.get(index);
        components.remove(index);
        updateComponentOffsets(index);
        position.getAndUpdate(offset -> offset - component.length);
    }

    private long readableBytes(HttpData httpData) throws IOException {
        if (httpData.isInMemory()) {
            ByteBuf byteBuf = httpData.getByteBuf();
            if (byteBuf != null) {
                return byteBuf.readableBytes();
            } else {
                return 0;
            }
        } else {
            return httpData.length();
        }
    }

    private void updateComponentOffsets(int index) {
        int size = components.size();
        if (size <= index) {
            return;
        }

        Component c = components.get(index);
        if (index == 0) {
            c.offset = 0;
            index++;
        }

        for (int i = index; i < size; i++) {
            Component prev = components.get(i - 1);
            Component cur = components.get(i);
            cur.offset = prev.length;
        }
    }

    /**
     * Closes any file related access if the upload is on
     * disk and releases the buffer for the file.
     */
    void destroy() {
        fileAccess.getAndUpdate(channel -> {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    LOG.warn("Error closing file channel for disk file upload", e);
                }
            }
            return null;
        });
        data.release();
    }

    /**
     * Represents a section of the http data.
     */
    public final class Component {

        private final int length;
        private long offset;

        private Component(int length, long offset) {
            this.length = length;
            this.offset = offset;
        }

        private ByteBuf createDelegate(ByteBuf byteBuf, BiPredicate<ByteBuf, Integer> onRelease) {
            if (byteBuf == null) {
                return Unpooled.EMPTY_BUFFER;
            }
            return new ByteBufDelegate(byteBuf) {
                @Override
                public boolean release() {
                    return onRelease.test(byteBuf, 1);
                }

                @Override
                public boolean release(int decrement) {
                    return onRelease.test(byteBuf, decrement);
                }
            };
        }

        /**
         * @return A buffer that holds the data for this section. The
         * caller is responsible for releasing the buffer.
         *
         * @throws IOException If the buffer could not be obtained
         */
        public ByteBuf getByteBuf() throws IOException {
            if (length == 0) {
                return Unpooled.EMPTY_BUFFER;
            }
            if (data.isInMemory()) {
                ByteBuf byteBuf = data.getByteBuf();
                int index = components.indexOf(this);
                if (byteBuf instanceof CompositeByteBuf) {
                    CompositeByteBuf compositeByteBuf = (CompositeByteBuf) byteBuf;
                    return createDelegate(compositeByteBuf.internalComponent(index), (buf, count) -> {
                        compositeByteBuf.removeComponent(index);
                        removeComponent(index);
                        return true;
                    });
                } else {
                    return createDelegate(byteBuf, (buf, count) -> {
                        //needs to be retrieved again because the internal reference
                        //may have changed
                        try {
                            ByteBuf currentBuffer = data.getByteBuf();
                            if (currentBuffer instanceof CompositeByteBuf) {
                                ((CompositeByteBuf) currentBuffer).removeComponent(index);
                            } else {
                                data.delete();
                            }
                        } catch (IOException e) { }
                        removeComponent(index);
                        return true;
                    });
                }
            } else {
                byte[] data = new byte[length];
                fileAccess.get().getChannel().read(ByteBuffer.wrap(data), offset);
                return Unpooled.wrappedBuffer(data);
            }
        }
    }

}
