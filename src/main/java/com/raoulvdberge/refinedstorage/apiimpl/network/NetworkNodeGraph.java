package com.raoulvdberge.refinedstorage.apiimpl.network;

import com.google.common.collect.Sets;
import com.raoulvdberge.refinedstorage.RSBlocks;
import com.raoulvdberge.refinedstorage.api.network.INetworkNodeGraph;
import com.raoulvdberge.refinedstorage.api.network.INetworkNodeVisitor;
import com.raoulvdberge.refinedstorage.api.network.node.INetworkNode;
import com.raoulvdberge.refinedstorage.api.network.node.INetworkNodeProxy;
import com.raoulvdberge.refinedstorage.item.ItemBlockController;
import com.raoulvdberge.refinedstorage.tile.TileController;
import net.minecraft.block.state.IBlockState;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;
import java.util.Set;

import static com.raoulvdberge.refinedstorage.proxy.CapabilityNetworkNodeProxy.NETWORK_NODE_PROXY_CAPABILITY;

public class NetworkNodeGraph implements INetworkNodeGraph {
    private TileController controller;
    private Set<INetworkNode> nodes = Sets.newConcurrentHashSet();

    public NetworkNodeGraph(TileController controller) {
        this.controller = controller;
    }

    @Override
    public void rebuild() {
        if (!controller.canRun()) {
            if (!nodes.isEmpty()) {
                disconnectAll();
            }

            return;
        }

        Operator operator = new Operator();

        BlockPos controllerPos = controller.getPos();
        World controllerWorld = controller.getWorld();

        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos pos = controllerPos.offset(facing);
            operator.apply(controllerWorld, pos, facing.getOpposite());
        }

        Visitor currentVisitor;
        while ((currentVisitor = operator.toCheck.poll()) != null) {
            currentVisitor.visit(operator);
        }

        this.nodes = operator.newNodes;

        for (INetworkNode node : operator.previousNodes) {
            node.onDisconnected(controller);

            operator.changed = true;
        }

        if (operator.changed) {
            controller.getDataManager().sendParameterToWatchers(TileController.NODES);
        }
    }

    @Override
    public Collection<INetworkNode> all() {
        return nodes;
    }

    @Override
    public void disconnectAll() {
        nodes.forEach(n -> n.onDisconnected(controller));
        nodes.clear();

        controller.getDataManager().sendParameterToWatchers(TileController.NODES);
    }

    protected World getWorld() {
        return controller.getWorld();
    }

    private void removeOtherController(World world, BlockPos otherControllerPos) {
        if (!controller.getPos().equals(otherControllerPos)) {
            IBlockState state = world.getBlockState(otherControllerPos);

            ItemStack stackToSpawn = ItemBlockController.createStackWithNBT(new ItemStack(RSBlocks.CONTROLLER, 1, state.getBlock().getMetaFromState(state)));

            world.setBlockToAir(otherControllerPos);

            InventoryHelper.spawnItemStack(
                world,
                otherControllerPos.getX(),
                otherControllerPos.getY(),
                otherControllerPos.getZ(),
                stackToSpawn
            );
        }
    }

    private class Operator implements INetworkNodeVisitor.Operator {
        private Set<INetworkNode> newNodes = Sets.newConcurrentHashSet();
        private Set<INetworkNode> previousNodes = Sets.newConcurrentHashSet(nodes);

        private boolean changed;

        private Queue<Visitor> toCheck = new ArrayDeque<>();

        @Override
        public void apply(World world, BlockPos pos, EnumFacing side) {
            TileEntity tile = world.getTileEntity(pos);

            if (tile != null) {
                if (tile instanceof TileController) {
                    removeOtherController(world, pos);
                } else if (tile.hasCapability(NETWORK_NODE_PROXY_CAPABILITY, side)) {
                    INetworkNodeProxy otherNodeProxy = NETWORK_NODE_PROXY_CAPABILITY.cast(tile.getCapability(NETWORK_NODE_PROXY_CAPABILITY, side));
                    INetworkNode otherNode = otherNodeProxy.getNode();

                    if (newNodes.add(otherNode)) {
                        if (!nodes.contains(otherNode)) {
                            otherNode.onConnected(controller);

                            changed = true;
                        }

                        previousNodes.remove(otherNode);

                        toCheck.add(new Visitor(otherNode, world, pos, side, tile));
                    }
                }
            }
        }
    }

    private class Visitor implements INetworkNodeVisitor {
        private final INetworkNode node;
        private final World world;
        private final BlockPos pos;
        private final EnumFacing side;
        private final TileEntity tile;

        Visitor(INetworkNode node, World world, BlockPos pos, EnumFacing side, TileEntity tile) {
            this.node = node;
            this.world = world;
            this.pos = pos;
            this.side = side;
            this.tile = tile;
        }

        @Override
        public void visit(Operator operator) {
            if (node instanceof INetworkNodeVisitor) {
                ((INetworkNodeVisitor) node).visit(operator);
            } else {
                for (EnumFacing checkSide : EnumFacing.VALUES) {
                    if (checkSide != side) { // Avoid going backward
                        INetworkNodeProxy nodeOnSideProxy = NETWORK_NODE_PROXY_CAPABILITY.cast(tile.getCapability(NETWORK_NODE_PROXY_CAPABILITY, checkSide));
                        INetworkNode nodeOnSide = nodeOnSideProxy.getNode();

                        if (nodeOnSide == node) {
                            operator.apply(world, pos.offset(checkSide), checkSide.getOpposite());
                        }
                    }
                }
            }
        }
    }
}
