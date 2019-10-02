// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

import java8.util.Comparators;
import java8.util.function.Supplier;
import java.util.zip.*;
import com.machinezoo.noexception.*;

import java8.util.stream.Collectors;
import java8.util.stream.StreamSupport;

class TransparencyZip extends FingerprintTransparency {
	private final ZipOutputStream zip;
	private int offset;
	TransparencyZip(OutputStream stream) {
		zip = new ZipOutputStream(stream);
	}
	@Override protected void log(String keyword, Map<String, Supplier<ByteBuffer>> data) {
		Exceptions.sneak().run(() -> {
			List<String> suffixes = StreamSupport.stream(data.keySet())
				.sorted(Comparators.comparing(ext -> {
					if (ext.equals(".json"))
						return 1;
					if (ext.equals(".dat"))
						return 2;
					return 3;
				}))
				.collect(Collectors.toList());
			for (String suffix : suffixes) {
				++offset;
				zip.putNextEntry(new ZipEntry(String.format("%03d", offset) + "-" + keyword + suffix));
				ByteBuffer buffer = data.get(suffix).get();
				WritableByteChannel output = Channels.newChannel(zip);
				while (buffer.hasRemaining())
					output.write(buffer);
				zip.closeEntry();
			}
		});
	}

	@Override public void close() {
		Exceptions.sneak().run(zip::close);
	}
}
