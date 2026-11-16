package com.ocarinaengine.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaBladeTracker;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.screen.UiShapes;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders the Ocarina of Time Z-Targeting visuals and sword slash trails:
 * - 3D rotating triangle reticle orbiting the locked entity (or hovering above a candidate target);
 * - Glowing 3D sword slash ribbon arcs during attacks & Spin Attack.
 */
public final class ZeldaTargetRenderer {
	private ZeldaTargetRenderer() {}

	public static void register() {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(ZeldaTargetRenderer::renderWorldOverlays);
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderHud(graphics));
	}

	private static void renderWorldOverlays(WorldRenderContext context) {
		if (!OcarinaGameTypeClient.isZelda() || ZeldaChestCutscene.isActive()) {
			return;
		}
		PoseStack pose = context.matrixStack();
		if (pose == null) {
			return;
		}
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		Vec3 camPos = context.camera().getPosition();
		float time = (System.nanoTime() % 1_000_000_000_000L) / 1.0e9f;

		LivingEntity locked = ZeldaTargetingSystem.getLockedTarget();
		if (locked != null) {
			renderLockedReticle(context, pose, camPos, locked, partialTick, time);
		} else {
			LivingEntity candidate = ZeldaTargetingSystem.getCandidateTarget();
			if (candidate != null) {
				renderCandidatePointer(context, pose, camPos, candidate, partialTick, time);
			}
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && !ZeldaBladeTracker.samples().isEmpty()) {
			renderSwordTrail(pose, camPos, player, partialTick);
		}

		float impactProg = ZeldaCombatSystem.getImpactBurstProgress();
		if (impactProg >= 0.0f) {
			renderImpactBurst(context, pose, camPos, ZeldaCombatSystem.getLastImpactPos(),
					impactProg, ZeldaCombatSystem.isLastImpactHeavy());
		}
	}

	private static void renderLockedReticle(WorldRenderContext context, PoseStack pose, Vec3 camPos,
											LivingEntity target, float partialTick, float time) {
		double tx = Mth.lerp(partialTick, target.xo, target.getX());
		double ty = Mth.lerp(partialTick, target.yo, target.getY()) + target.getBbHeight() * 0.56;
		double tz = Mth.lerp(partialTick, target.zo, target.getZ());

		boolean hostile = ZeldaTargetingSystem.isTargetHostile(target);
		int r = hostile ? 255 : 90;
		int g = hostile ? 58 : 255;
		int b = hostile ? 62 : 130;

		pose.pushPose();
		pose.translate(tx - camPos.x, ty - camPos.y, tz - camPos.z);
		pose.mulPose(context.camera().rotation());

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f mat = pose.last().pose();

		float snapU = ZeldaTargetingSystem.getLockSnapProgress();
		float invSnap = 1.0f - snapU;
		float snapEase = 1.0f - invSnap * invSnap * invSnap;
		float snapScale = Mth.lerp(snapEase, 1.92f, 1.0f);
		float pulse = 0.92f + 0.08f * Mth.sin(time * 9.0f);
		float radius = Math.max(0.45f, target.getBbWidth() * 0.58f) * pulse * snapScale;
		float spin = time * 3.8f + (1.0f - snapEase) * 1.15f;

		// Four inward-pointing Ocarina-style lock triangles orbiting the target center.
		for (int i = 0; i < 4; i++) {
			float angle = spin + i * (Mth.HALF_PI);
			float cx = Mth.cos(angle) * radius;
			float cy = Mth.sin(angle) * radius;

			float inX = -Mth.cos(angle);
			float inY = -Mth.sin(angle);
			float perpX = -inY;
			float perpY = inX;

			float tipLen = 0.16f;
			float baseHalf = 0.11f;
			float tipX = cx + inX * tipLen;
			float tipY = cy + inY * tipLen;
			float b1x = cx - inX * 0.08f + perpX * baseHalf;
			float b1y = cy - inY * 0.08f + perpY * baseHalf;
			float b2x = cx - inX * 0.08f - perpX * baseHalf;
			float b2y = cy - inY * 0.08f - perpY * baseHalf;

			buf.addVertex(mat, tipX, tipY, 0.0f).setColor(255, 255, 235, 235);
			buf.addVertex(mat, b1x, b1y, 0.0f).setColor(r, g, b, 195);
			buf.addVertex(mat, b2x, b2y, 0.0f).setColor(r, g, b, 195);
		}

		// Vertical Navi-style hovering downward triangle right above the target's head.
		float headTopY = target.getBbHeight() * 0.58f + 0.34f + Mth.sin(time * 6.5f) * 0.06f;
		buf.addVertex(mat, 0.0f, headTopY, 0.0f).setColor(255, 255, 240, 240);
		buf.addVertex(mat, -0.14f, headTopY + 0.24f, 0.0f).setColor(r, g, b, 215);
		buf.addVertex(mat, 0.14f, headTopY + 0.24f, 0.0f).setColor(r, g, b, 215);

		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		pose.popPose();
	}

	private static void renderCandidatePointer(WorldRenderContext context, PoseStack pose, Vec3 camPos,
											   LivingEntity candidate, float partialTick, float time) {
		double tx = Mth.lerp(partialTick, candidate.xo, candidate.getX());
		double ty = Mth.lerp(partialTick, candidate.yo, candidate.getY()) + candidate.getBbHeight() + 0.35
				+ Mth.sin(time * 5.0f) * 0.06;
		double tz = Mth.lerp(partialTick, candidate.zo, candidate.getZ());

		boolean hostile = ZeldaTargetingSystem.isTargetHostile(candidate);
		int r = hostile ? 255 : 95;
		int g = hostile ? 68 : 255;
		int b = hostile ? 72 : 140;

		pose.pushPose();
		pose.translate(tx - camPos.x, ty - camPos.y, tz - camPos.z);
		pose.mulPose(context.camera().rotation());

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f mat = pose.last().pose();

		buf.addVertex(mat, 0.0f, 0.0f, 0.0f).setColor(255, 255, 230, 185);
		buf.addVertex(mat, -0.11f, 0.19f, 0.0f).setColor(r, g, b, 150);
		buf.addVertex(mat, 0.11f, 0.19f, 0.0f).setColor(r, g, b, 150);

		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		pose.popPose();
	}

	private static void renderSwordTrail(PoseStack pose, Vec3 camPos, LocalPlayer player, float partialTick) {
		java.util.List<ZeldaBladeTracker.Sample> samples = ZeldaBladeTracker.samples();
		if (samples.size() < 2) {
			return;
		}
		long now = System.nanoTime();
		long life = ZeldaBladeTracker.trailLifeNanos();
		long maxGap = ZeldaBladeTracker.maxGapNanos();

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f mat = pose.last().pose();
		for (int i = 1; i < samples.size(); i++) {
			ZeldaBladeTracker.Sample a = samples.get(i - 1);
			ZeldaBladeTracker.Sample b = samples.get(i);
			if (b.nanos - a.nanos > maxGap) {
				continue;
			}
			float fadeA = trailFade(now - a.nanos, life) * a.intensity;
			float fadeB = trailFade(now - b.nanos, life) * b.intensity;
			if (fadeA <= 0.01f && fadeB <= 0.01f) {
				continue;
			}
			int kind = b.kind;
			int cr = (kind == 2) ? 255 : (kind == 1 ? 255 : 175);
			int cg = (kind == 2) ? 215 : (kind == 1 ? 145 : 232);
			int cb = (kind == 2) ? 85 : (kind == 1 ? 210 : 255);

			Vec3 aEdge = a.tip.add(a.tip.subtract(a.base).normalize().scale(0.10));
			Vec3 bEdge = b.tip.add(b.tip.subtract(b.base).normalize().scale(0.10));

			int aBase = Math.round(60 * fadeA);
			int bBase = Math.round(60 * fadeB);
			int aTip = Math.round(190 * fadeA);
			int bTip = Math.round(190 * fadeB);
			int aCore = Math.round(235 * fadeA);
			int bCore = Math.round(235 * fadeB);

			trailVertex(buf, mat, a.base, camPos, cr, cg, cb, aBase);
			trailVertex(buf, mat, a.tip, camPos, cr, cg, cb, aTip);
			trailVertex(buf, mat, b.tip, camPos, cr, cg, cb, bTip);

			trailVertex(buf, mat, a.base, camPos, cr, cg, cb, aBase);
			trailVertex(buf, mat, b.tip, camPos, cr, cg, cb, bTip);
			trailVertex(buf, mat, b.base, camPos, cr, cg, cb, bBase);

			trailVertex(buf, mat, a.tip, camPos, cr, cg, cb, aTip);
			trailVertex(buf, mat, aEdge, camPos, 255, 255, 255, aCore);
			trailVertex(buf, mat, bEdge, camPos, 255, 255, 255, bCore);

			trailVertex(buf, mat, a.tip, camPos, cr, cg, cb, aTip);
			trailVertex(buf, mat, bEdge, camPos, 255, 255, 255, bCore);
			trailVertex(buf, mat, b.tip, camPos, cr, cg, cb, bTip);
		}

		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}

	/** 1 for a brand new sample, easing to 0 at the end of the ribbon's life. */
	private static float trailFade(long ageNanos, long lifeNanos) {
		float t = Mth.clamp(ageNanos / (float) lifeNanos, 0.0f, 1.0f);
		float k = 1.0f - t;
		return k * k;
	}

	private static void trailVertex(BufferBuilder buf, Matrix4f mat, Vec3 p, Vec3 camPos, int r, int g, int b, int a) {
		buf.addVertex(mat, (float) (p.x - camPos.x), (float) (p.y - camPos.y), (float) (p.z - camPos.z))
				.setColor(r, g, b, Mth.clamp(a, 0, 255));
	}

	private static void addDiamondBeam(BufferBuilder buf, Matrix4f mat, float dirX, float dirY,
									   float halfLen, float halfWidth, int r, int g, int b, int coreAlpha, int edgeAlpha) {
		float perpX = -dirY * halfWidth;
		float perpY = dirX * halfWidth;
		float tipX = dirX * halfLen;
		float tipY = dirY * halfLen;

		buf.addVertex(mat, tipX, tipY, 0.0f).setColor(r, g, b, 0);
		buf.addVertex(mat, perpX, perpY, 0.0f).setColor(r, g, b, coreAlpha);
		buf.addVertex(mat, -perpX, -perpY, 0.0f).setColor(r, g, b, edgeAlpha);

		buf.addVertex(mat, -tipX, -tipY, 0.0f).setColor(r, g, b, 0);
		buf.addVertex(mat, -perpX, -perpY, 0.0f).setColor(r, g, b, edgeAlpha);
		buf.addVertex(mat, perpX, perpY, 0.0f).setColor(r, g, b, coreAlpha);
	}

	private static void renderImpactBurst(WorldRenderContext context, PoseStack pose, Vec3 camPos,
									  Vec3 impactPos, float progress, boolean heavy) {
		float fade = (1.0f - progress) * (1.0f - progress);
		if (fade <= 0.02f) {
			return;
		}

		pose.pushPose();
		pose.translate(impactPos.x - camPos.x, impactPos.y - camPos.y, impactPos.z - camPos.z);
		pose.mulPose(context.camera().rotation());

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f mat = pose.last().pose();

		float scale = (heavy ? 1.58f : 1.18f) * (0.42f + 0.90f * progress);
		float cutAngle = ZeldaCombatSystem.getLastImpactCutAngleRad();
		float cutDx = Mth.cos(cutAngle);
		float cutDy = Mth.sin(cutAngle);
		float crossDx = -cutDy;
		float crossDy = cutDx;

		float beamHalfLen = scale * (heavy ? 1.65f : 1.32f);
		int haloAlpha = Math.round(215 * fade);
		int coreAlpha = Math.round(255 * fade);

		addDiamondBeam(buf, mat, cutDx, cutDy, beamHalfLen, scale * 0.24f, 255, 130, 195, haloAlpha, Math.round(170 * fade));
		addDiamondBeam(buf, mat, cutDx, cutDy, beamHalfLen * 0.88f, scale * 0.085f, 255, 255, 255, coreAlpha, coreAlpha);
		addDiamondBeam(buf, mat, crossDx, crossDy, scale * 0.72f, scale * 0.11f, 255, 165, 215, Math.round(165 * fade), Math.round(120 * fade));

		int sparks = heavy ? 14 : 10;
		float travel = (0.18f + 0.98f * progress) * scale;
		for (int i = 0; i < sparks; i++) {
			float jitter = ((i % 3) - 1) * 0.14f;
			float a = cutAngle + i * (Mth.TWO_PI / sparks) + jitter;
			float dx = Mth.cos(a);
			float dy = Mth.sin(a);
			float px = -dy * (scale * 0.036f);
			float py = dx * (scale * 0.036f);

			float rBase = travel * ((i % 2 == 0) ? 0.52f : 0.34f);
			float rTip = rBase + scale * ((i % 2 == 0) ? 0.56f : 0.38f) * (1.0f - 0.35f * progress);
			int sAlpha = Math.round(225 * fade);

			buf.addVertex(mat, dx * rTip, dy * rTip, 0.0f).setColor(255, 155, 215, 0);
			buf.addVertex(mat, dx * rBase + px, dy * rBase + py, 0.0f).setColor(255, 255, 255, sAlpha);
			buf.addVertex(mat, dx * rBase - px, dy * rBase - py, 0.0f).setColor(255, 130, 195, sAlpha);
		}

		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		pose.popPose();
	}

	private static void renderHud(GuiGraphics g) {
		if (!OcarinaGameTypeClient.isZelda() || ZeldaChestCutscene.isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.screen != null) {
			return;
		}
		float blend = ZeldaTargetingSystem.getTargetBlend();
		if (blend <= 0.02f) {
			return;
		}

		LivingEntity locked = ZeldaTargetingSystem.getLockedTarget();
		if (locked != null) {
			String name = locked.getDisplayName().getString();
			boolean hostile = ZeldaTargetingSystem.isTargetHostile(locked);
			int accent = UiShapes.withAlpha(hostile ? 0xFFFF5252 : 0xFF66FF99, blend);
			int textCol = UiShapes.withAlpha(0xFFF2E8D0, blend);
			int tw = mc.font.width(name);
			int cx = g.guiWidth() / 2;
			g.drawString(mc.font, name, cx - tw / 2 + 4, 5, textCol, true);
			UiShapes.arrow(g, cx - tw / 2 - 4, 9, 3, false, accent);
		}
	}
}
