/*
 * Copyright (c) 2008-2016, GigaSpaces Technologies, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gigaspaces.internal.io;

import com.gigaspaces.internal.utils.pool.IMemoryAwareResourceFactory;
import com.gigaspaces.internal.utils.pool.IMemoryAwareResourcePool;
import com.gigaspaces.logger.Constants;
import com.j_spaces.kernel.SystemProperties;
import com.j_spaces.kernel.pool.IResourceFactory;
import com.j_spaces.kernel.pool.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;


/**
 * Notice! this implementation is not Thread safe and should be use in conjunction with {@link
 * com.j_spaces.kernel.pool.ResourcePool} only.
 *
 * @author Guy Korland
 * @since 4.1
 */
@com.gigaspaces.api.InternalApi
public class CompressedMarshObjectConvertor
        extends Resource
        implements MarshObjectConvertorResource {
    private int zipEntryCounter = 0;
    private static final int MAX_ENTRIES = 100;

    private int _level;

    private GSByteArrayOutputStream _bao;
    private final SmartByteArrayCache _byteArrayCache;
    private ZipOutputStream _zo;
    private ObjectOutputStream _oo;

    private GSByteArrayInputStream _bai;
    private ZipInputStream _zi;
    private ObjectInputStream _oi;

    static private CompressedMarshObjectConvertorFactory _factory = null;

    // logger
    private static final Logger _logger = Logger.getLogger(Constants.LOGGER_LRMI);
    private static final byte[] DUMMY_BUFFER = new byte[0];

    private static SmartByteArrayCache createSerializationByteArrayCache(ISmartLengthBasedCacheCallback cacheCallback) {
        final int maxBufferSize = Integer.getInteger(SystemProperties.STORAGE_TYPE_SERIALIZATION_MAX_CACHED_BUFFER_SIZE, SystemProperties.STORAGE_TYPE_SERIALIZATION_MAX_CACHED_BUFFER_SIZE_DEFAULT);
        final double expungeRatio = Double.parseDouble(System.getProperty(SystemProperties.STORAGE_TYPE_SERIALIZATION_CACHED_BUFFER_EXPUNGE_RATIO, String.valueOf(SystemProperties.STORAGE_TYPE_SERIALIZATION_CACHED_BUFFER_EXPUNGE_RATIO_DEFAULT)));
        final int expungeCount = Integer.getInteger(SystemProperties.STORAGE_TYPE_SERIALIZATION_CACHED_BUFFER_EXPUNGE_TIMES_THRESHOLD, SystemProperties.STORAGE_TYPE_SERIALIZATION_CACHED_BUFFER_EXPUNGE_TIMES_THRESHOLD_DEFAULT);
        return new SmartByteArrayCache(maxBufferSize, expungeRatio, expungeCount, 1024, cacheCallback);
    }


    public CompressedMarshObjectConvertor(int level) {
        this(level, null);
    }

    /**
     * @param level the compression level (0-9), The default setting is DEFAULT_COMPRESSION.
     * @throws IllegalArgumentException if the compression level is invalid
     */
    public CompressedMarshObjectConvertor(int level, ISmartLengthBasedCacheCallback cacheCallback) {
        _byteArrayCache = createSerializationByteArrayCache(cacheCallback);
        _level = level;
        try {
            _bao = new GSByteArrayOutputStream();
            _zo = new ZipOutputStream(_bao);
            _zo.setLevel(_level);
            _zo.putNextEntry(new ZipEntry(Integer.toString(zipEntryCounter++)));
            _oo = getObjectOutputStream(_zo);

            _bai = new GSByteArrayInputStream(new byte[0]);
            _zi = new ZipInputStream(_bai);

            getObject(getMarshObjectInternal("", true)); // remove header from
            // in/out
        } catch (Exception e) {
            if (_logger.isLoggable(Level.SEVERE)) {
                _logger.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    private MarshObject getMarshObjectInternal(Object o, boolean init) throws IOException {
        byte[] bc;

        if (init) {
            bc = serializeToByteArray(o);
        } else {
            _bao.setBuffer(_byteArrayCache.get());
            _bao.reset();
            // check for next time
            if (++zipEntryCounter < MAX_ENTRIES) {
                _zo.putNextEntry(new ZipEntry(Integer.toString(zipEntryCounter)));
                _oo.reset();
            } else // open new zip OutputStream for next time
            {
                zipEntryCounter = 0;
                _zo = new ZipOutputStream(_bao);
                _zo.setLevel(_level);
                _zo.putNextEntry(new ZipEntry(Integer.toString(zipEntryCounter)));
                _oo = getObjectOutputStream(_zo);

                // remove ObjectOutputStream header from zip stream
                _zo.closeEntry();
                _bao.reset();

                _zo.putNextEntry(new ZipEntry(Integer.toString(++zipEntryCounter)));
                _oo.reset();
            }

            bc = serializeToByteArray(o);
            _byteArrayCache.notifyUsedSize(bc.length);
            _bao.setBuffer(DUMMY_BUFFER);
        }


        return new CompressedMarshObject(bc);
    }

    protected byte[] serializeToByteArray(Object o) throws IOException {
        _oo.writeObject(o);
        _oo.flush();
        _zo.closeEntry();

        return _bao.toByteArray();
    }

    /*
     * (non-Javadoc)
     * @see
     * com.j_spaces.kernel.lrmi.IMarshObjectConvertor#getMarshObject(java.lang
     * .Object)
     */
    public MarshObject getMarshObject(Object o) throws IOException {
        return getMarshObjectInternal(o, false);

    }

    /*
     * (non-Javadoc)
     * @see
     * com.j_spaces.kernel.lrmi.IMarshObjectConvertor#getObject(com.j_spaces
     * .kernel.lrmi.MarshObject)
     */
    public Object getObject(MarshObject marsh) throws IOException,
            ClassNotFoundException {
        if (!(marsh instanceof CompressedMarshObject))
            throw new IOException("Can decompress only CompressedMarshObject");

        _bai.setBuffer(marsh.getBytes());
        _zi.getNextEntry();

        if (_oi == null) {
            _oi = getObjectInputStream(_zi);
        }

        Object object = _oi.readObject();
        // It seems ZipInputStream has some internal state and it needs
        // to be cleared both before and after setting the underlying
        // InputStream
        // buffer
        _zi.getNextEntry();
        _bai.setBuffer(DUMMY_BUFFER);
        return object;
    }

    protected ObjectOutputStream getObjectOutputStream(OutputStream is)
            throws IOException {
        return new ObjectOutputStream(is);
    }

    protected ObjectInputStream getObjectInputStream(InputStream is)
            throws IOException {
        return new ObjectInputStream(is);
    }

    @Override
    public void clear() {
        // No clear needed
    }

    public static IResourceFactory<CompressedMarshObjectConvertor> getFactory() {
        if (_factory == null)
            _factory = new CompressedMarshObjectConvertorFactory();

        return _factory;
    }

    protected static class CompressedMarshObjectConvertorFactory
            implements IMemoryAwareResourceFactory<CompressedMarshObjectConvertor> {
        public CompressedMarshObjectConvertor allocate() {
            return new CompressedMarshObjectConvertor(9);
        }

        @Override
        public CompressedMarshObjectConvertor allocate(
                IMemoryAwareResourcePool resourcePool) {
            return new CompressedMarshObjectConvertor(9, SmartLengthBasedCache.toCacheCallback(resourcePool));
        }
    }

    @Override
    public long getUsedMemory() {
        return _byteArrayCache.getLength();
    }

}
