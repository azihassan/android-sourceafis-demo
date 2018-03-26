// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java8.util.function.Function;
import java8.util.function.Predicate;
import java8.util.stream.Collectors;
import java8.util.stream.Stream;
import java8.util.stream.StreamSupport;

import java.util.*;

class JsonSkeleton {
	int width;
	int height;
	List<Cell> minutiae;
	List<JsonSkeletonRidge> ridges;
	JsonSkeleton(Skeleton skeleton) {
		width = skeleton.size.x;
		height = skeleton.size.y;
		final Map<SkeletonMinutia, Integer> offsets = new HashMap<>();
		for (int i = 0; i < skeleton.minutiae.size(); ++i)
			offsets.put(skeleton.minutiae.get(i), i);
		this.minutiae = StreamSupport.stream(skeleton.minutiae).map(new Function<SkeletonMinutia, Cell>() {
			@Override
			public Cell apply(SkeletonMinutia m) {
				return m.position;
			}
		}).collect(Collectors.<Cell>toList());
		ridges = StreamSupport.stream(skeleton.minutiae)
			.flatMap(new Function<SkeletonMinutia, Stream<JsonSkeletonRidge>>() {
				@Override
				public Stream<JsonSkeletonRidge> apply(SkeletonMinutia m) {
					return StreamSupport.stream(m.ridges)
							.filter(new Predicate<SkeletonRidge>() {
								@Override
								public boolean test(SkeletonRidge r) {
									return r.points instanceof CircularList;
								}
							})
							.map(new Function<SkeletonRidge, JsonSkeletonRidge>() {
								@Override
								public JsonSkeletonRidge apply(SkeletonRidge r) {
									JsonSkeletonRidge jr = new JsonSkeletonRidge();
									jr.start = offsets.get(r.start());
									jr.end = offsets.get(r.end());
									jr.length = r.points.size();
									return jr;
								}
							});
				}
			})
			.collect(Collectors.<JsonSkeletonRidge>toList());
	}
}
