package com.glaciersecurity.glaciermessenger.utils;

import java.security.SecureRandom;

public class RandomNameGenerator {

	private static final String[] adjectives = {
			"brave", "mysterious", "swift", "shiny", "clever",
			"bold", "fierce", "radiant", "silent", "ancient",
			"vibrant", "majestic", "gentle", "wild", "ethereal",
			"serene", "mystic", "stalwart", "dynamic", "luminous",
			"tenacious", "calm", "graceful", "intrepid", "noble",
			"wondrous", "lush", "resilient", "breathtaking", "harmonious",
			"eager", "daring"
	};

	private static final String[] nouns = {
			"river", "mountain", "forest", "ocean", "sky",
			"meadow", "canyon", "stone", "cloud", "valley",
			"stream", "desert", "tide", "grove",
			"rain", "field", "horizon", "blossom", "summit",
			"island", "fjord", "dune", "lagoon", "prairie",
			"pebble", "cove", "breeze", "root", "basin",
			"flora", "fauna"
	};

	public static String generateRandomName() {
		SecureRandom random = new SecureRandom();
		String adjective = adjectives[random.nextInt(adjectives.length)];
		String noun = nouns[random.nextInt(nouns.length)];
		int num = random.nextInt(100);

		return adjective + "_" + noun + "_" + num;
	}
}
