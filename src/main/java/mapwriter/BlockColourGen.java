package mapwriter;

import java.util.List;

import mapwriter.region.BlockColours;
import mapwriter.region.BlockColours.BlockType;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

// Static class to generate BlockColours.
// This is separate from BlockColours because it needs to run in the GL rendering thread
// whereas the generated BlockColours object is used only in the background thread.
// So basically split to make it clear that BlockColourGen and the generated BlockColours
// must not have any interaction after it is generated.

public class BlockColourGen {

	// Returns {minU, maxU, minV, maxV} (atlas-space) of the top (UP) face of the block's
	// baked model. Replacement for block.getIcon(1, meta) from 1.7.
	private static float[] getTopFaceUV(Block block, int dv) {

		// air, barrier, etc: 1.7 returned a null icon for these
		if (block.getRenderType() == -1) {
			return null;
		}

		IBlockState state = block.getStateFromMeta(dv);
		BlockModelShapes shapes = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes();
		IBakedModel model = shapes.getModelForState(state);

		if (model == shapes.getModelManager().getMissingModel()) {
			// built-in blocks (water, lava, ...) have no model; vanilla's particle sprite lookup handles them
			TextureAtlasSprite sprite = shapes.getTexture(state);
			if (sprite == null || sprite == Minecraft.getMinecraft().getTextureMapBlocks().getMissingSprite()) {
				return null;
			}
			return new float[] { sprite.getMinU(), sprite.getMaxU(), sprite.getMinV(), sprite.getMaxV() };
		}

		List<BakedQuad> quads = model.getFaceQuads(EnumFacing.UP);
		if (quads.isEmpty()) {
			// cross models (flowers, etc.) have no face-culled quads
			quads = model.getGeneralQuads();
		}
		if (quads.isEmpty()) {
			return null;
		}

		// vertex data layout: 7 ints per vertex (x, y, z, colour, u, v, lightmap)
		int[] data = quads.get(0).getVertexData();
		float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
		float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float u = Float.intBitsToFloat(data[i * 7 + 4]);
			float v = Float.intBitsToFloat(data[i * 7 + 5]);
			minU = Math.min(minU, u);
			maxU = Math.max(maxU, u);
			minV = Math.min(minV, v);
			maxV = Math.max(maxV, v);
		}
		return new float[] { minU, maxU, minV, maxV };
	}

	private static int getIconMapColour(float[] uv, Texture terrainTexture) {
		// uv = {minU, maxU, minV, maxV}, already ordered so min <= max.
		int iconX = (int) Math.round(((float) terrainTexture.w) * uv[0]);
		int iconY = (int) Math.round(((float) terrainTexture.h) * uv[2]);
		int iconWidth = (int) Math.round(((float) terrainTexture.w) * (uv[1] - uv[0]));
		int iconHeight = (int) Math.round(((float) terrainTexture.h) * (uv[3] - uv[2]));

		int[] pixels = new int[iconWidth * iconHeight];

		//MwUtil.log("(%d, %d) %dx%d", iconX, iconY, iconWidth, iconHeight);

		terrainTexture.getRGB(iconX, iconY, iconWidth, iconHeight, pixels, 0, iconWidth);

		// need to use custom averaging routine rather than scaling down to one pixel to
		// stop transparent pixel colours being included in the average.
		return Render.getAverageColourOfArray(pixels);
	}

	private static int adjustBlockColourFromType(BlockColours bc, int blockAndMeta, int blockColour) {
		// for normal blocks multiply the block colour by the render colour.
		// for other blocks the block colour will be multiplied by the biome colour.
		int blockid = blockAndMeta >> 4;
		Block block = (Block) Block.blockRegistry.getObjectById(blockid);
		BlockType blockType = bc.getBlockType(blockAndMeta);
		switch (blockType) {

			case OPAQUE:
				blockColour |= 0xff000000;
			case NORMAL:
				// fix crash when mods don't implement getRenderColor for all
				// block meta values.
				try {
					IBlockState state = block.getStateFromMeta(blockAndMeta & 0xf);
					int renderColour = block.getRenderColor(state);
					if (renderColour != 0xffffff) {
						blockColour = Render.multiplyColours(blockColour, 0xff000000 | renderColour);
					}
				} catch (RuntimeException e) {
					// do nothing
				}
				break;
			case LEAVES:
				// leaves look weird on the map if they are not opaque.
				// they also look too dark if the render colour is applied.
				blockColour |= 0xff000000;
				break;
			default:
				break;
		}
		return blockColour;
	}

	private static void genBiomeColours(BlockColours bc) {
		// generate array of foliage, grass, and water colour multipliers
		// for each biome.

		for (int i = 0; i < BiomeGenBase.getBiomeGenArray().length; i++) {
			BiomeGenBase biome = BiomeGenBase.getBiomeGenArray()[i];
			if (biome != null) {
				bc.setBiomeWaterShading(i, biome.getWaterColorMultiplier() & 0xffffff);
				bc.setBiomeGrassShading(i, biome.getGrassColorAtPos(BlockPos.ORIGIN) & 0xffffff); //FIXME 0,0,0?
				bc.setBiomeFoliageShading(i, biome.getFoliageColorAtPos(BlockPos.ORIGIN) & 0xffffff); //FIXME 0,0,0?
			} else {
				bc.setBiomeWaterShading(i, 0xffffff);
				bc.setBiomeGrassShading(i, 0xffffff);
				bc.setBiomeFoliageShading(i, 0xffffff);
			}
		}
	}

	public static void genBlockColours(BlockColours bc) {

		MwUtil.log("generating block map colours from textures");

		int terrainTextureId = Minecraft.getMinecraft().renderEngine.getTexture(TextureMap.locationBlocksTexture).getGlTextureId();

		// create texture object from the currently bound GL texture
		if (terrainTextureId == 0) {
			MwUtil.log("error: could get terrain texture ID");
			return;
		}
		Texture terrainTexture = new Texture(terrainTextureId);

		double u1Last = 0;
		double u2Last = 0;
		double v1Last = 0;
		double v2Last = 0;
		int blockColourLast = 0;
		int e_count = 0;
		int b_count = 0;
		int s_count = 0;

		for (int blockID = 0; blockID < 4096; blockID++) { //TODO: replace hardcoded 4096 with actual registry size
			for (int dv = 0; dv < 16; dv++) {

				int blockAndMeta = ((blockID & 0xfff) << 4) | (dv & 0xf);
				Block block = (Block) Block.blockRegistry.getObjectById(blockID);
				int blockColour = 0;

				// in 1.8 the registry returns air for unregistered ids instead of null
				if (block != null && Block.getIdFromBlock(block) == blockID) {

					float[] uv = null;
					try {
						uv = getTopFaceUV(block, dv);
					} catch (Exception e) {
						//MwUtil.log("genFromTextures: exception caught when requesting block texture for %03x:%x", blockID, dv);
						//e.printStackTrace();
						e_count++;
					}

					if (uv != null) {
						double u1 = uv[0];
						double u2 = uv[1];
						double v1 = uv[2];
						double v2 = uv[3];

						if ((u1 == u1Last) && (u2 == u2Last) && (v1 == v1Last) && (v2 == v2Last)) {
							blockColour = blockColourLast;
							s_count++;
						} else {
							blockColour = getIconMapColour(uv, terrainTexture);
							u1Last = u1;
							u2Last = u2;
							v1Last = v1;
							v2Last = v2;
							blockColourLast = blockColour;
							b_count++;
						}
						//if (dv == 0)
						//	MwUtil.log("block %03x:%x colour = %08x", blockID, dv, blockColour);
					}

					// doesn't work as some leaves blocks aren't rendered using the biome
					// foliage colour
					//try {
					//	if (block.isLeaves(null, 0, 0, 0)) {
					//		bc.setBlockType(blockAndMeta, BlockType.LEAVES);
					//	}
					//} catch (NullPointerException e) {
					//}

					blockColour = adjustBlockColourFromType(bc, blockAndMeta, blockColour);
				}
				bc.setColour(blockAndMeta, blockColour);
			}
		}

		MwUtil.log("processed %d block textures, %d skipped, %d exceptions", b_count, s_count, e_count);

		genBiomeColours(bc);
	}
}