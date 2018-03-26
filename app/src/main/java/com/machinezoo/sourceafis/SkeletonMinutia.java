// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java.nio.*;
import java.util.*;
import java8.util.function.Predicate;
import java8.util.function.ToIntFunction;
import java8.util.stream.StreamSupport;

class SkeletonMinutia {
	final Cell position;
	final List<SkeletonRidge> ridges = new ArrayList<>();
	SkeletonMinutia(Cell position) {
		this.position = position;
	}
	void attachStart(SkeletonRidge ridge) {
		if (!ridges.contains(ridge)) {
			ridges.add(ridge);
			ridge.start(this);
		}
	}
	void detachStart(SkeletonRidge ridge) {
		if (ridges.contains(ridge)) {
			ridges.remove(ridge);
			if (ridge.start() == this)
				ridge.start(null);
		}
	}
	void write(ByteBuffer buffer) {
		for (SkeletonRidge ridge : ridges)
			if (ridge.points instanceof CircularList)
				ridge.write(buffer);
	}
	int serializedSize() {
		return StreamSupport.stream(ridges).filter(new Predicate<SkeletonRidge>() {
			@Override
			public boolean test(SkeletonRidge r) {
				return r.points instanceof CircularList;
			}
		}).mapToInt(new ToIntFunction<SkeletonRidge>() {
			@Override
			public int applyAsInt(SkeletonRidge r) {
				return r.serializedSize();
			}
		}).sum();
	}
	@Override public String toString() {
		return String.format("%s*%d", position.toString(), ridges.size());
	}
}
