// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

import java8.util.Comparators;
import java8.util.function.*;
import java.util.zip.*;
import com.machinezoo.noexception.*;
import com.machinezoo.noexception.throwing.ThrowingRunnable;
import java8.util.stream.Collectors;
import java8.util.stream.StreamSupport;

class TransparencyZip extends FingerprintTransparency {
	private final ZipOutputStream zip;
	private int offset;
	TransparencyZip(OutputStream stream) {
		zip = new ZipOutputStream(stream);
	}
	@Override protected void log(final String keyword, final Map<String, Supplier<ByteBuffer>> data) {
		final Set<String> keySet = data.keySet();
		Exceptions.sneak().run(new ThrowingRunnable() {
			@Override
			public void run() throws Exception {
				List<String> suffixes = (List<String>) StreamSupport.stream(keySet)
                        .sorted(Comparators.comparing(new Function<String, Comparable>() {
                            @Override
                            public Comparable apply(String ext) {
                                if (ext.equals(".json"))
                                    return 1;
                                if (ext.equals(".dat"))
                                    return 2;
                                return 3;
                            }
                        }))
                        .collect(Collectors.<String>toList());
				for (String suffix : suffixes) {
					++offset;
					zip.putNextEntry(new ZipEntry(String.format("%03d", offset) + "-" + keyword + suffix));
					ByteBuffer buffer = data.get(suffix).get();
					WritableByteChannel output = Channels.newChannel(zip);
					while (buffer.hasRemaining())
						output.write(buffer);
					zip.closeEntry();
				}
			}
		});
	}

	@Override public void close() {
		Exceptions.sneak().run(new ThrowingRunnable() {
			@Override
			public void run() throws Exception {
				zip.close();
			}
		});
	}
}
