package schmoller.tubes.parts;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Icon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.ForgeDirection;
import schmoller.tubes.ITube;
import schmoller.tubes.ITubeConnectable;
import schmoller.tubes.InventoryHelper;
import schmoller.tubes.ModTubes;
import schmoller.tubes.TubeHelper;
import schmoller.tubes.TubeItem;
import schmoller.tubes.TubeRegistry;
import schmoller.tubes.definitions.TubeDefinition;
import schmoller.tubes.logic.TubeLogic;
import schmoller.tubes.network.packets.ModPacketAddItem;
import schmoller.tubes.render.RenderTubePart;
import codechicken.core.data.MCDataInput;
import codechicken.core.data.MCDataOutput;
import codechicken.core.lighting.LazyLightMatrix;
import codechicken.core.vec.Cuboid6;
import codechicken.core.vec.Vector3;
import codechicken.microblock.HollowMicroblock;
import codechicken.multipart.IconHitEffects;
import codechicken.multipart.JCuboidPart;
import codechicken.multipart.JIconHitEffects;
import codechicken.multipart.JNormalOcclusion;
import codechicken.multipart.NormalOcclusionTest;
import codechicken.multipart.TFacePart;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TSlottedPart;
import codechicken.multipart.TileMultipart;
import codechicken.multipart.scalatraits.TSlottedTile;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class BaseTubePart extends JCuboidPart implements ITube, JNormalOcclusion, JIconHitEffects, TSlottedPart
{
	private LinkedList<TubeItem> mItemsInTransit = new LinkedList<TubeItem>();

	public static int transitTime = 1000;
	public static int blockedWaitTime = 10;
	
	private TubeLogic mLogic;
	private TubeDefinition mDef;
	private String mType;
	
	private boolean mIsBlocked = false;
	private float[] mBlockedProgress = null;
	
	private static final int NO_ROUTE = -1;
	private static final int ROUTE_TERM = -2;
	
	public BaseTubePart()
	{
		
	}
	
	public BaseTubePart(String type)
	{
		mDef = TubeRegistry.instance().getDefinition(type);
		mLogic = mDef.getTubeLogic(this);
		mType = type;
	}
	
	@Override
	public String getType()
	{
		return "tubes_" + mType;
	}

	@Override
	public Cuboid6 getBounds()
	{
		return new Cuboid6(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
	}

	@Override
	public boolean occlusionTest( TMultiPart npart )
	{
		return NormalOcclusionTest.apply(this, npart);
	}
	
	@Override
	public Iterable<Cuboid6> getOcclusionBoxes()
	{
		ArrayList<Cuboid6> boxes = new ArrayList<Cuboid6>();
		boxes.add(getBounds());
		return boxes;
	}
	
	@Override
	public ItemStack pickItem( MovingObjectPosition hit )
	{
		return ModTubes.itemTube.createForType(mType);
	}
	
	@Override
	public Iterable<ItemStack> getDrops()
	{
		ArrayList<ItemStack> stacks = new ArrayList<ItemStack>(mItemsInTransit.size());
		for(TubeItem item : mItemsInTransit)
			stacks.add(item.item);
		
		stacks.add(ModTubes.itemTube.createForType(mType));
		
		return stacks;
	}
	
	public TubeLogic getLogic()
	{
		return mLogic;
	}
	
	@Override
	public boolean addItem(ItemStack item, int fromDir)
	{
		assert(fromDir >= -1 && fromDir < 6);
		
		
		TubeItem tItem = new TubeItem(item);
		if(fromDir == -1)
		{
			tItem.direction = 6;
			tItem.progress = 0.5f;
		}
		else
		{
			tItem.direction = fromDir;
			tItem.progress = 0;
		}
		
		if(!canAddItem(tItem))
			return false;
		
		mLogic.onItemEnter(tItem);
		
		if(!world().isRemote)
		{
			mItemsInTransit.add(tItem);
			
			if(tItem.direction != 6)
				addToClient(tItem);
		}
		
		return true;
	}
	
	@Override
	public boolean addItem(TubeItem item)
	{
		assert(item.direction >= -1 && item.direction <= 6);
		
		mLogic.onItemEnter(item);
		mItemsInTransit.add(item);
		return true;
	}
	
	@Override
	public boolean canAddItem( TubeItem item )
	{
		if(mIsBlocked && mBlockedProgress != null && item.direction != 6)
		{
			if(mBlockedProgress[item.direction] < 0.1)
				return false;
		}
		return mLogic.canItemEnter(item, (item.direction == 6 ? 6 : item.direction^1));
	}
	
	@Override
	public boolean canPathThrough()
	{
		return mLogic.canPathThrough();
	}
	
	@Override
	public int getConnections()
	{
		return TubeHelper.getConnectivity(world(), x(), y(), z());
	}
	
	private int getNumConnections()
	{
		int count = 0;
		int con = getConnections();
		for(int i = 0; i < 6; ++i)
		{
			if((con & (1 << i)) != 0)
				++count;
		}
		
		return count;
	}
	
	@Override
	public int getConnectableMask()
	{
		int con = mLogic.getConnectableMask();
		
		if (tile() instanceof TSlottedTile)
		{
			for(int i = 0; i < 6; ++i)
			{
				TMultiPart part = tile().partMap(i);
				if(part instanceof TFacePart && !(part instanceof HollowMicroblock))
					con -= (con & (1 << i));
			}
		}
		return con;
	}
	
	private boolean mDidRoute = false;
	private int getNextDirection(TubeItem item)
	{
		int count = 0;
		int dir = NO_ROUTE;
		
		mDidRoute = false;
		
		int conns = getConnections();
		
		conns -= (conns & (1 << (item.direction ^ 1)));
		
		for(int i = 0; i < 6; ++i)
		{
			if((conns & (1 << i)) != 0)
			{
				++count;
				dir = i;
			}
		}
		
		if(count > 1)
		{
			if(world().isRemote)
				return ROUTE_TERM;
			
			if(count > 1)
			{
				if(mLogic.hasCustomRouting())
					dir = mLogic.onDetermineDestination(item);
				else
					dir = TubeHelper.findNextDirection(world(), x(), y(), z(), item);
				
				mDidRoute = true;
			}
		}
		
		return dir;
	}
	
	@Override
	public void onPartChanged()
	{
//		world().loadedTileEntityList.remove(tile());
//		world().addTileEntity(tile());
	}
	
	@Override
	public void bind( TileMultipart t )
	{
		if(tile() != null && tile() != t)
		{
			world().loadedTileEntityList.remove(tile());
			world().addTileEntity(t);
		}
		
		super.bind(t);
	}
	
	@Override
	public boolean doesTick()
	{
		return true;
	}
	
	private void setBlocked(boolean blocked)
	{
		if(blocked == mIsBlocked)
			return;
		
		if(blocked)
		{
			mIsBlocked = true;
			scheduleTick(blockedWaitTime);
		}
		else
		{
			mIsBlocked = false;
			mBlockedProgress = null;
		}
		
		if(!world().isRemote)
			tile().getWriteStream(this).writeByte(1).writeBoolean(mIsBlocked);
	}
	
	@Override
	public void scheduledTick()
	{
		if(!mIsBlocked)
			return;
		
		// See if the item is unstuck now
		for(TubeItem item : mItemsInTransit)
		{
			if(!item.updated && item.progress >= 0.5)
			{
				int lastDir = item.direction;
				item.direction = getNextDirection(item);
				if(item.direction == NO_ROUTE)
				{
					item.direction = lastDir;
				}
				else
				{
					setBlocked(false);
					item.updated = true;
					addToClient(item);
				}
			}
			
			if(item.progress + 0.1 >= 1)
			{
				if(transferToNext(item))
				{
					mItemsInTransit.remove(item);
					setBlocked(false);
					return;
				}
			}
		}
		
		if(mIsBlocked)
			scheduleTick(blockedWaitTime);
	}
	
	private void addToClient(TubeItem item)
	{
		if(world().isRemote)
			return;
		
		item.write(tile().getWriteStream(this).writeByte(0));
	}
	
	@Override
	public void update()
	{
		Iterator<TubeItem> it = mItemsInTransit.iterator();
		
		while(it.hasNext())
		{
			TubeItem item = it.next();
			
			if(item.direction == 6) // It needs a path right away
			{
				if(mIsBlocked)
					continue;
				
				item.direction = getNextDirection(item);
				if(item.direction == NO_ROUTE)
				{
					item.direction = 6;
					
					setBlocked(true);
					addToClient(item);
					break;
				}
				else if(item.direction == ROUTE_TERM)
				{
					it.remove();
					continue;
				}
				// Tell the client that the item got a direction
				else if(!world().isRemote)
					addToClient(item);
			}
			
			if(mIsBlocked && mBlockedProgress != null)
			{
				if(item.progress > mBlockedProgress[item.direction] - 0.1)
				{
					if(item.progress <= mBlockedProgress[item.direction])
						mBlockedProgress[item.direction] -= 0.1;
					
					continue;
				}
			}
			
			item.progress += 0.1;
			
			if(!item.updated && item.progress >= 0.5)
			{
				if(mIsBlocked && mBlockedProgress == null)
				{
					item.progress -= 0.1;
					mBlockedProgress = new float[] {item.progress, item.progress, item.progress, item.progress, item.progress, item.progress};
					continue;
				}
				
				// Find new direction to go
				int lastDir = item.direction;
				item.direction = getNextDirection(item);
				item.updated = true;
				if(item.direction == NO_ROUTE)
				{
					if(getNumConnections() == 1)
						item.direction = lastDir ^ 1;
					else
					{
						item.direction = lastDir;
						item.updated = false;
						setBlocked(true);
						addToClient(item); // Client will have deleted it
					}
					break;
				}
				else if(item.direction == ROUTE_TERM)
				{
					it.remove();
					continue;
				}
				// Synch the new direction to client
				else if(!world().isRemote && mDidRoute)
					addToClient(item);
				
				
			}
			
			if(item.progress >= 1)
			{
				if(transferToNext(item))
					it.remove();
				else if (mIsBlocked)
				{
					item.progress -= 0.1;
					mBlockedProgress = new float[] {item.progress, item.progress, item.progress, item.progress, item.progress, item.progress};
					continue;
				}
				else
				{
					item.progress -= 1;
					item.updated = false;
					item.direction ^= 1;
					
					if(!world().isRemote)
						addToClient(item);
				}
			}
		}
	}
	
	
	public List<TubeItem> getItems()
	{
		return mItemsInTransit;
	}
	
	private boolean transferToNext(TubeItem item)
	{
		if(item.direction == 6)
			return false;
		
		ForgeDirection dir = ForgeDirection.getOrientation(item.direction);
		
		TileEntity ent = world().getBlockTileEntity(x() + dir.offsetX, y() + dir.offsetY, z() + dir.offsetZ);
		ITubeConnectable con = TubeHelper.getTubeConnectable(ent);
		if(con != null)
		{
			if(con instanceof ITube)
			{
				if(((ITube)con).isBlocked() && !con.canAddItem(item))
				{
					setBlocked(true);
					return false;
				}
			}
			
			item.progress -= 1;
			item.updated = false;
			
			return con.addItem(item);
		}
		
		if(world().isRemote)
			return true;
		
		if(ent != null && InventoryHelper.canAcceptItem(item.item, world(), ent.xCoord, ent.yCoord, ent.zCoord, item.direction))
		{
			InventoryHelper.insertItem(item.item, world(), ent.xCoord, ent.yCoord, ent.zCoord, item.direction);
			
			if(item.item.stackSize == 0)
				return true;
		}
		
		return false;
	}

	
	@Override
	public int getRouteWeight()
	{
		return getLogic().getRouteWeight();
	}

	@Override
	public boolean isBlocked()
	{
		return mIsBlocked;
	}
	
	@Override
	public void save( NBTTagCompound root )
	{
		NBTTagList list = new NBTTagList();
		for(TubeItem item : mItemsInTransit)
		{
			NBTTagCompound tag = new NBTTagCompound();
			item.writeToNBT(tag);
			list.appendTag(tag);
		}
		
		root.setTag("items", list);
	}
	
	@Override
	public void load( NBTTagCompound root )
	{
		mItemsInTransit.clear();
		
		NBTTagList list = root.getTagList("items");
		
		for(int i = 0; i < list.tagCount(); ++i)
		{
			NBTTagCompound tag = (NBTTagCompound)list.tagAt(i);
			
			//mItemsInTransit.add(TubeItem.readFromNBT(tag));
		}
	}
	
	@Override
	public void writeDesc( MCDataOutput packet )
	{
		packet.writeShort(mItemsInTransit.size());
		for(TubeItem item : mItemsInTransit)
			item.write(packet);
	}
	
	@Override
	public void readDesc( MCDataInput packet )
	{
		int count = packet.readShort() & 0xFFFF;
		
		mItemsInTransit.clear();
		
		for(int i = 0; i < count; ++i)
			mItemsInTransit.add(TubeItem.read(packet));
	}
	
	@Override
	public void read( MCDataInput packet )
	{
		int id = packet.readByte();
		switch(id)
		{
		case 0:
			mItemsInTransit.add(TubeItem.read(packet));
			break;
		case 1:
			mIsBlocked = packet.readBoolean();
			break;
		}
	}
	
	@Override
	@SideOnly( Side.CLIENT )
	public void renderDynamic( Vector3 pos, float frame, int pass )
	{
		RenderTubePart.instance().setIcons(mDef.getCenterIcon(), mDef.getStraightIcon());
		RenderTubePart.instance().renderDynamic(this, pos);
	}
	
	@Override
	@SideOnly( Side.CLIENT )
	public void renderStatic(Vector3 pos, LazyLightMatrix olm, int pass) 
	{
		RenderTubePart.instance().setIcons(mDef.getCenterIcon(), mDef.getStraightIcon());
		RenderTubePart.instance().renderStatic(this);
	}
	
	@Override
	@SideOnly( Side.CLIENT )
	public void addHitEffects( MovingObjectPosition hit, EffectRenderer effectRenderer )
	{
		IconHitEffects.addHitEffects(this, hit, effectRenderer);
	}
	
	@Override
	@SideOnly( Side.CLIENT )
	public void addDestroyEffects( EffectRenderer effectRenderer )
	{
		IconHitEffects.addDestroyEffects(this, effectRenderer);
	}
	
	@Override
	@SideOnly( Side.CLIENT )
	public Icon getBreakingIcon( Object subPart, int side )
	{
		return getBrokenIcon(side);
	}
	
	@Override
	@SideOnly( Side.CLIENT )
	public Icon getBrokenIcon( int side )
	{
		return mDef.getCenterIcon();
	}

	@Override
	public int getSlotMask()
	{
		return 1 << 6;
	}
	
	
}