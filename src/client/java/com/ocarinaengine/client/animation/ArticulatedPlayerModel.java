package com.ocarinaengine.client.animation;

import net.minecraft.client.model.geom.ModelPart;

/**
 * Exposes articulated forearm and lower-leg (shin) ModelParts added to PlayerModel
 * so procedural animations, held-item rendering, and blade tracking can drive elbow
 * and knee flexion cleanly without reflection.
 */
public interface ArticulatedPlayerModel {
	ModelPart ocarina$rightForearm();

	ModelPart ocarina$leftForearm();

	ModelPart ocarina$rightShin();

	ModelPart ocarina$leftShin();

	ModelPart ocarina$rightForearmSleeve();

	ModelPart ocarina$leftForearmSleeve();

	ModelPart ocarina$rightShinPants();

	ModelPart ocarina$leftShinPants();

	boolean ocarina$isSlim();
}
