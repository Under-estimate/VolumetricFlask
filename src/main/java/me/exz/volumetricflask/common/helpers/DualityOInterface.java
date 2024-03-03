package me.exz.volumetricflask.common.helpers;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.implementations.tiles.ICraftingMachine;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.core.settings.TickRates;
import appeng.fluids.helper.DualityFluidInterface;
import appeng.fluids.helper.IFluidInterfaceHost;
import appeng.fluids.util.AEFluidInventory;
import appeng.fluids.util.IAEFluidInventory;
import appeng.fluids.util.IAEFluidTank;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.ConfigManager;
import appeng.util.InventoryAdaptor;
import appeng.util.inv.AdaptorItemHandler;
import me.exz.volumetricflask.Items;
import me.exz.volumetricflask.common.items.ItemVolumetricFlask;
import me.exz.volumetricflask.utils.FluidAdaptor;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

import static net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY;

public class DualityOInterface extends DualityInterface implements IAEFluidInventory {

    private final AEFluidInventory tanks = new AEFluidInventory(this, 1, Fluid.BUCKET_VOLUME * 64);
    private static final String FLUID_NBT_KEY = "storage_fluid";
    private final HashMap<Fluid, ArrayList<ItemStack>> fillMap = new HashMap<>();
    private static final String FILL_MAP_NBT_KEY = "fill_map";

    public DualityOInterface(AENetworkProxy networkProxy, IInterfaceHost ih) {
        super(((Supplier<AENetworkProxy>) () -> {
            networkProxy.setVisualRepresentation(new ItemStack(Items.ITEM_PART_O_INTERFACE));
            return networkProxy;
        }).get(), ih);
    }

    @SuppressWarnings("unchecked")
    public <T> T getPrivateValue(String fieldName) {
        return (T) getPrivateValue(this, fieldName);
    }

    @SuppressWarnings("unchecked")
    public <T> T getPrivateValue(DualityInterface instance, String fieldName) {
        return (T) ReflectionHelper.getPrivateValue(DualityInterface.class, instance, fieldName);
    }

    public <E> void setPrivateValue(E value, String fieldName) {
        setPrivateValue(this, value, fieldName);
    }

    public <E> void setPrivateValue(DualityInterface instance, E value, String fieldName) {
        ReflectionHelper.setPrivateValue(DualityInterface.class, instance, value, fieldName);
    }


    @Override
    public boolean pushPattern(ICraftingPatternDetails patternDetails, InventoryCrafting table) {
        AENetworkProxy gridProxy = getPrivateValue("gridProxy");
        List<ICraftingPatternDetails> craftingList = getPrivateValue("craftingList");
        IInterfaceHost iHost = getPrivateValue("iHost");

        if (this.hasItemsToSend() || !gridProxy.isActive() || !craftingList.contains(patternDetails)) {
            return false;
        }

        final TileEntity tile = iHost.getTileEntity();
        final World w = tile.getWorld();

        final EnumSet<EnumFacing> possibleDirections = iHost.getTargets();
        for (final EnumFacing s : possibleDirections) {
            final TileEntity te = w.getTileEntity(tile.getPos().offset(s));
            if (te instanceof IInterfaceHost) {
                try {
                    AENetworkProxy gridProxy2 = getPrivateValue(((IInterfaceHost) te).getInterfaceDuality(), "gridProxy");
                    if (gridProxy2.getGrid() == gridProxy.getGrid()) {
                        continue;
                    }
                } catch (final GridAccessException e) {
                    continue;
                }
                continue;
            }

            if (te instanceof IFluidInterfaceHost) {
                try {
                    AENetworkProxy gridProxy2 = ReflectionHelper.getPrivateValue(DualityFluidInterface.class, ((IFluidInterfaceHost) te).getDualityFluidInterface(), "gridProxy");
                    if (gridProxy2.getGrid() == gridProxy.getGrid()) {
                        continue;
                    }
                } catch (GridAccessException e) {
                    continue;
                }
            }

            if (te instanceof ICraftingMachine) {
                final ICraftingMachine cm = (ICraftingMachine) te;
                if (cm.acceptsPlans()) {
                    if (cm.pushPattern(patternDetails, table, s.getOpposite())) {
                        return true;
                    }
                    continue;
                }
                continue;
            }
            final InventoryAdaptor ad = InventoryAdaptor.getAdaptor(te, s.getOpposite());
            final FluidAdaptor fad = FluidAdaptor.getAdaptor(te, s.getOpposite());
            if (ad == null && fad == null) {
                //no inventory and no tank
                continue;
            }
            //is blocking
            if (this.isBlocking()) {
                if (ad != null) {
                    if (!ad.simulateRemove(1, ItemStack.EMPTY, null).isEmpty()) {
                        continue;
                    }
                }
                if (fad != null) {
                    if (!fad.isEmpty()) {
                        continue;
                    }
                }
            }

            NonNullList<ItemStack> netStackList = NonNullList.withSize(table.getSizeInventory(), ItemStack.EMPTY);
            ArrayList<ItemStack> matchedFilledFlaskList = new ArrayList<>();

            for (int x = 0; x < table.getSizeInventory(); x++) {
                final ItemStack is = table.getStackInSlot(x);
                netStackList.set(x, is.copy());
            }
            for (IAEItemStack aeItemStack : patternDetails.getOutputs()) {
                final ItemStack outputItemStack = aeItemStack.createItemStack();
                if (outputItemStack.getItem() instanceof ItemVolumetricFlask) {
                    if (!isEmptyVolumetricFlask(outputItemStack)) {
                        //is filled volumetric flask
                        for (ItemStack is : netStackList) {
                            if (isEmptyVolumetricFlask(is)) {
                                //is empty volumetric flask
                                if (is.getItem() == outputItemStack.getItem()) {
                                    //same capacity, aka same item
                                    int matchedCount = Math.min(is.getCount(), outputItemStack.getCount());
                                    ItemStack matchedFilledFlask = outputItemStack.copy();
                                    matchedFilledFlask.setCount(matchedCount);
                                    matchedFilledFlaskList.add(matchedFilledFlask);
                                    outputItemStack.setCount(outputItemStack.getCount() - matchedCount);
                                    is.setCount(is.getCount() - matchedCount);
                                    if (outputItemStack.isEmpty()) {
                                        //skip left input ItemStack
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }


            //determine whether pattern has flask or other items
            boolean hasOther = false;
            boolean hasFlask = false;
            for (final ItemStack is : netStackList) {
                if (!is.isEmpty()) {
                    if (is.getItem() instanceof ItemVolumetricFlask && !isEmptyVolumetricFlask(is)) {
                        hasFlask = true;
                    } else {
                        hasOther = true;
                    }
                }
            }

            if (hasOther) {
                if (ad == null || !this.acceptsItems(ad, table)) {
                    continue;
                }
            }
            if (hasFlask) {
                if (fad == null || !this.acceptsFluid(fad, table)) {
                    continue;
                }
            }

            for (ItemStack matchedFilledFlask : matchedFilledFlaskList) {
                //noinspection ConstantConditions
                Fluid fluid = matchedFilledFlask.getCapability(FLUID_HANDLER_ITEM_CAPABILITY, null).getTankProperties()[0].getContents().getFluid();
                if (fillMap.containsKey(fluid)) {
                    fillMap.get(fluid).add(matchedFilledFlask);
                } else {
                    ArrayList<ItemStack> itemStackList = new ArrayList<>();
                    itemStackList.add(matchedFilledFlask);
                    fillMap.put(fluid, itemStackList);
                }
            }

            for (final ItemStack is : netStackList) {
                if (!is.isEmpty()) {
                    if (is.getItem() instanceof ItemVolumetricFlask && fad != null) {
                        final ItemStack added = fad.addFlask(is);
                        this.addToSendList(added);
                        ItemStack emptyVolumetricFlask = new ItemStack(is.getItem(), is.getCount() - added.getCount());
                        emptyVolumetricFlask.setTagCompound(new NBTTagCompound());
                        InventoryAdaptor iad = new AdaptorItemHandler(this.getInternalInventory());
                        iad.addItems(emptyVolumetricFlask);
                    } else if (ad != null) {
                        final ItemStack added = ad.addItems(is);
                        this.addToSendList(added);
                    }
                }
            }
            this.pushItemsOut(possibleDirections);
            return true;
        }

        return false;
    }

    private boolean isEmptyVolumetricFlask(ItemStack is) {
        if (is.getItem() instanceof ItemVolumetricFlask) {
            //noinspection ConstantConditions
            return is.getCapability(FLUID_HANDLER_ITEM_CAPABILITY, null).getTankProperties()[0].getContents() == null;
        }
        return false;
    }

    private boolean hasItemsToSend() {
        List<ItemStack> waitingToSend = getPrivateValue("waitingToSend");
        return waitingToSend != null && !waitingToSend.isEmpty();
    }

    private boolean hasFluid() {
        IAEFluidStack aeFluidStack = this.tanks.getFluidInSlot(0);
        return aeFluidStack != null && aeFluidStack.getStackSize() != 0;
    }

    @Override
    public TickingRequest getTickingRequest(final IGridNode node) {
        return new TickingRequest(TickRates.Interface.getMin(), TickRates.Interface.getMax(), !this.hasWorkToDo(), true);
    }

    @Override
    public TickRateModulation tickingRequest(final IGridNode node, final int ticksSinceLastCall) {
        AENetworkProxy gridProxy = getPrivateValue("gridProxy");

        if (!gridProxy.isActive()) {
            return TickRateModulation.SLEEP;
        }

        IInterfaceHost iHost = getPrivateValue("iHost");
        if (this.hasItemsToSend()) {
            this.pushItemsOut(iHost.getTargets());
        }
        boolean pushedFluid = false;
        if (this.hasFluid()) {
            pushedFluid = this.pushFluid();
        }
        Method updateStorage = ReflectionHelper.findMethod(DualityInterface.class, "updateStorage", null);

        try {
            final boolean couldDoWork = (boolean) updateStorage.invoke(this);
            if (this.hasWorkToDo()) {
                if (couldDoWork || pushedFluid) {
                    return TickRateModulation.URGENT;
                } else {
                    return TickRateModulation.SLOWER;
                }
            }
            return TickRateModulation.SLEEP;
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return TickRateModulation.SLEEP;
    }

    private void pushItemsOut(final EnumSet<EnumFacing> possibleDirections) {
        if (!this.hasItemsToSend()) {
            return;
        }

        IInterfaceHost iHost = getPrivateValue("iHost");
        List<ItemStack> waitingToSend = getPrivateValue("waitingToSend");

        final TileEntity tile = iHost.getTileEntity();
        final World w = tile.getWorld();

        final Iterator<ItemStack> i = waitingToSend.iterator();
        while (i.hasNext()) {
            ItemStack whatToSend = i.next();
            if (whatToSend.getItem() instanceof ItemVolumetricFlask) {
                for (final EnumFacing s : possibleDirections) {
                    final TileEntity te = w.getTileEntity(tile.getPos().offset(s));
                    if (te == null) {
                        continue;
                    }
                    final FluidAdaptor fad = FluidAdaptor.getAdaptor(te, s.getOpposite());
                    if (fad != null) {
                        final ItemStack result = fad.addFlask(whatToSend);
                        ItemStack emptyVolumetricFlask = new ItemStack(whatToSend.getItem(), whatToSend.getCount() - result.getCount());
                        emptyVolumetricFlask.setTagCompound(new NBTTagCompound());
                        InventoryAdaptor iad = new AdaptorItemHandler(this.getInternalInventory());
                        iad.addItems(emptyVolumetricFlask);
                        if (result.isEmpty()) {
                            whatToSend = ItemStack.EMPTY;
                        } else {
                            whatToSend.setCount(whatToSend.getCount() - (whatToSend.getCount() - result.getCount()));
                        }

                        if (whatToSend.isEmpty()) {
                            break;
                        }
                    }
                }
            } else {
                for (final EnumFacing s : possibleDirections) {
                    final TileEntity te = w.getTileEntity(tile.getPos().offset(s));
                    if (te == null) {
                        continue;
                    }
                    final InventoryAdaptor ad = InventoryAdaptor.getAdaptor(te, s.getOpposite());
                    if (ad != null) {
                        final ItemStack result = ad.addItems(whatToSend);

                        if (result.isEmpty()) {
                            whatToSend = ItemStack.EMPTY;
                        } else {
                            whatToSend.setCount(whatToSend.getCount() - (whatToSend.getCount() - result.getCount()));
                        }
                        if (whatToSend.isEmpty()) {
                            break;
                        }
                    }
                }
            }

            if (whatToSend.isEmpty()) {
                i.remove();
            }
        }

        if (waitingToSend.isEmpty()) {
            setPrivateValue(null, "waitingToSend");
        }
    }

    private boolean isBlocking() {
        ConfigManager cm = getPrivateValue("cm");
        return cm.getSetting(Settings.BLOCK) == YesNo.YES;
    }

    private boolean acceptsItems(final InventoryAdaptor ad, final InventoryCrafting table) {
        for (int x = 0; x < table.getSizeInventory(); x++) {
            final ItemStack is = table.getStackInSlot(x);
            if (is.isEmpty()) {
                continue;
            }
            if (is.getItem() instanceof ItemVolumetricFlask) {
                continue;
            }

            if (!ad.simulateAdd(is.copy()).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private boolean acceptsFluid(final FluidAdaptor fad, final InventoryCrafting table) {
        for (int x = 0; x < table.getSizeInventory(); x++) {
            final ItemStack is = table.getStackInSlot(x);
            if (is.isEmpty()) {
                continue;
            }
            if (!(is.getItem() instanceof ItemVolumetricFlask)) {
                continue;
            }

            if (isEmptyVolumetricFlask(is)) {
                continue;
            }

            if (!fad.simulateAdd(is.copy()).isEmpty()) {
                return false;
            }
            ItemStack emptyVolumetricFlask = new ItemStack(is.getItem(), is.getCount());
            emptyVolumetricFlask.setTagCompound(new NBTTagCompound());
            InventoryAdaptor iad = new AdaptorItemHandler(this.getInternalInventory());
            if (!iad.simulateAdd(emptyVolumetricFlask).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void addToSendList(final ItemStack is) {
        if (is.isEmpty()) {
            return;
        }
        AENetworkProxy gridProxy = getPrivateValue("gridProxy");
        List<ItemStack> waitingToSend = getPrivateValue("waitingToSend");
        if (waitingToSend == null) {
            waitingToSend = new ArrayList<>();
            setPrivateValue(waitingToSend, "waitingToSend");
//            waitingToSend = getThisValue("waitingToSend");
        }

        waitingToSend.add(is);

        try {
            gridProxy.getTick().wakeDevice(gridProxy.getNode());
        } catch (final GridAccessException e) {
            // :P
        }
    }

    @SuppressWarnings("unused")
    private IFluidHandler getFluidHandler(final TileEntity te, final EnumFacing d) {
        if (te != null && te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, d)) {
            return te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, d);
        }
        return null;
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        this.tanks.writeToNBT(data, FLUID_NBT_KEY);
        NBTTagCompound fillMapNbt = new NBTTagCompound();
        for (Map.Entry<Fluid, ArrayList<ItemStack>> fluidArrayListEntry : fillMap.entrySet()) {
            Fluid fluid = fluidArrayListEntry.getKey();
            ArrayList<ItemStack> itemStackArrayList = fluidArrayListEntry.getValue();
            NBTTagList itemNbtList = new NBTTagList();
            for (ItemStack itemStack : itemStackArrayList) {
                NBTTagCompound itemStackNbtTag = new NBTTagCompound();
                itemStack.writeToNBT(itemStackNbtTag);
                itemNbtList.appendTag(itemStackNbtTag);
            }
            fillMapNbt.setTag(fluid.getName(), itemNbtList);
        }
        data.setTag(FILL_MAP_NBT_KEY, fillMapNbt);
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        NBTTagCompound fillMapNbt = data.getCompoundTag(FILL_MAP_NBT_KEY);
        for (String fluidName : fillMapNbt.getKeySet()) {
            Fluid fluid = FluidRegistry.getFluid(fluidName);
            ArrayList<ItemStack> itemStackArrayList = new ArrayList<>();
            for (NBTBase itemStackNbtTag : fillMapNbt.getTagList(fluidName, 10)) {
                ItemStack itemStack = new ItemStack((NBTTagCompound) itemStackNbtTag);
                itemStackArrayList.add(itemStack);
            }
            fillMap.put(fluid, itemStackArrayList);
        }
        this.tanks.readFromNBT(data, FLUID_NBT_KEY);
    }

    private boolean hasWorkToDo() {
        if (this.hasItemsToSend() || this.hasFluid()) {
            return true;
        } else {
            IAEItemStack[] requireWork = getPrivateValue("requireWork");
            for (final IAEItemStack requiredWork : requireWork) {
                if (requiredWork != null) {
                    return true;
                }
            }

            return false;
        }
    }

    @Override
    public <T> T getCapability(Capability<T> capabilityClass, EnumFacing facing) {
        if (capabilityClass == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            //noinspection unchecked
            return (T) this.tanks;
        } else {
            return super.getCapability(capabilityClass, facing);
        }
    }

    @Override
    public boolean hasCapability(Capability<?> capabilityClass, EnumFacing facing) {
        return super.hasCapability(capabilityClass, facing) || capabilityClass == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY;
    }

    @Override
    public void onFluidInventoryChanged(IAEFluidTank inv, int slot) {
        AENetworkProxy gridProxy = getPrivateValue("gridProxy");
        try {
            if (this.hasFluid()) {
                gridProxy.getTick().alertDevice(gridProxy.getNode());
            } else {
                gridProxy.getTick().sleepDevice(gridProxy.getNode());

            }
        } catch (GridAccessException e) {
            e.printStackTrace();
        }
    }

    public boolean pushFluid() {
        boolean pushed = false;
        if (this.hasFluid()) {
            try {
                IAEFluidStack aeFluidStack = this.tanks.getFluidInSlot(0);
                Fluid aeFluidStackFluid = aeFluidStack.getFluid();
                if (fillMap.containsKey(aeFluidStackFluid)) {
                    //fill volumetric flask
                    ArrayList<ItemStack> fillList = fillMap.get(aeFluidStackFluid);
                    ItemStack currentItemStack = fillList.get(0);
                    long currentVolume = aeFluidStack.getStackSize();
                    //noinspection ConstantConditions
                    int capacity = currentItemStack.getCapability(FLUID_HANDLER_ITEM_CAPABILITY, null).getTankProperties()[0].getCapacity();
                    int filledCount = (int) Math.min(currentItemStack.getCount(), currentVolume / capacity);
                    if (filledCount != 0) {
                        AENetworkProxy gridProxy = getPrivateValue("gridProxy");
                        IMEInventory<IAEItemStack> dest = gridProxy.getStorage().getInventory(AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
                        IActionSource src = getPrivateValue("interfaceRequestSource");
                        ItemStack filledFlaskItemStack = currentItemStack.copy();
                        filledFlaskItemStack.setCount(filledCount);
                        IAEItemStack leftFilled = dest.injectItems(AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class).createStack(filledFlaskItemStack), Actionable.MODULATE, src);
                        int leftCount;
                        if (leftFilled == null) {
                            leftCount = 0;
                        } else {
                            leftCount = (int) leftFilled.getStackSize();
                        }
                        filledCount = filledCount - leftCount;
                        if (filledCount != 0) {
                            pushed = true;
                        }
                        int leftFilledCount = currentItemStack.getCount() - filledCount;
                        if (leftFilledCount == 0) {
                            fillList.remove(0);
                            if (fillList.isEmpty()) {
                                fillMap.remove(aeFluidStackFluid);
                            }
                        } else {
                            currentItemStack.setCount(leftFilledCount);
                        }
                        IAEFluidStack left = aeFluidStack.copy();
                        long leftVolume = currentVolume - capacity * filledCount;
                        if (leftVolume == 0) {
                            this.tanks.setFluidInSlot(0, null);
                        } else {
                            left.setStackSize(leftVolume);
                            this.tanks.setFluidInSlot(0, left);
                        }
                    }
                } else {
                    AENetworkProxy gridProxy = getPrivateValue("gridProxy");
                    IMEInventory<IAEFluidStack> dest = gridProxy.getStorage().getInventory(AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
                    IActionSource src = getPrivateValue("interfaceRequestSource");
                    IAEFluidStack left = dest.injectItems(aeFluidStack.copy(), Actionable.MODULATE, src);

                    long leftSize = 0;
                    if (left != null) {
                        leftSize = left.getStackSize();
                    }
                    if (aeFluidStack.getStackSize() != leftSize) {
                        pushed = true;
                    }
                    this.tanks.setFluidInSlot(0, left);
                }
            } catch (GridAccessException e) {
                e.printStackTrace();
            }
        }
        return pushed;
    }

    public void setPlacer(EntityPlayer player) {
        AENetworkProxy gridProxy = getPrivateValue("gridProxy");
        if (gridProxy != null) {
            gridProxy.setOwner(player);
        }
    }
}
