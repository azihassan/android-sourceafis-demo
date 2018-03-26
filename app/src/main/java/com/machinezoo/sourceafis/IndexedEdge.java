// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java.io.*;
import java.nio.*;
import java.util.*;
import com.machinezoo.noexception.*;
import com.machinezoo.noexception.throwing.ThrowingRunnable;
import gnu.trove.map.hash.*;

class IndexedEdge extends EdgeShape {
	final int reference;
	final int neighbor;
	IndexedEdge(Minutia[] minutiae, int reference, int neighbor) {
		super(minutiae[reference], minutiae[neighbor]);
		this.reference = reference;
		this.neighbor = neighbor;
	}
	void write(final DataOutputStream stream) {
		Exceptions.sneak().run(new ThrowingRunnable() {
			@Override
			public void run() throws Exception {
				stream.writeInt(reference);
				stream.writeInt(neighbor);
				stream.writeInt(length);
				stream.writeDouble(referenceAngle);
				stream.writeDouble(neighborAngle);
			}
		});
	}
	static ByteBuffer serialize(final TIntObjectHashMap<List<IndexedEdge>> hash) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final DataOutputStream formatter = new DataOutputStream(buffer);
		final int[] keys = hash.keys();
		Arrays.sort(keys);
		Exceptions.sneak().run(new ThrowingRunnable() {
			@Override
			public void run() throws Exception {
				formatter.writeInt(keys.length);
				for (int key : keys) {
					formatter.writeInt(key);
					List<IndexedEdge> edges = hash.get(key);
					formatter.writeInt(edges.size());
					for (IndexedEdge edge : edges)
						edge.write(formatter);
				}
				formatter.close();
			}
		});
		return ByteBuffer.wrap(buffer.toByteArray());
	}
}
