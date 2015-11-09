/*
 * Copyright (C) 2003-2015 Syed Asad Rahman <asad @ ebi.ac.uk>.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package uk.ac.ebi.reactionblast.mapping.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.graph.CycleFinder;
import org.openscience.cdk.graph.Cycles;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.tools.ILoggingTool;
import org.openscience.cdk.tools.LoggingToolFactory;
import org.openscience.smsd.AtomAtomMapping;
import org.openscience.smsd.Substructure;
import uk.ac.ebi.reactionblast.mapping.algorithm.Holder;
import uk.ac.ebi.reactionblast.mapping.container.ReactionContainer;
import uk.ac.ebi.reactionblast.mapping.helper.Debugger;

/**
 * @contact Syed Asad Rahman, EMBL-EBI, Cambridge, UK.
 * @author Syed Asad Rahman <asad @ ebi.ac.uk>
 */
public final class GraphMatcher extends Debugger {

    private final static boolean DEBUG = false;
    private final static ILoggingTool logger
            = LoggingToolFactory.createLoggingTool(GraphMatcher.class);

    /**
     *
     * @param mh
     * @return
     * @throws InterruptedException
     */
    public synchronized static Collection<MCSSolution> matcher(Holder mh) throws InterruptedException {
        ExecutorService executor = null;
        Collection<MCSSolution> mcsSolutions = Collections.synchronizedCollection(new ArrayList<MCSSolution>());

//        System.out.println(threadsAvailable + " threads to be used for graph matching for " + mh.getTheory());
        Set<Combination> jobReplicatorList = new TreeSet<>();
        int taskCounter = 0;

        try {
            ReactionContainer reactionStructureInformation = mh.getReactionContainer();
            Integer eductCount = reactionStructureInformation.getEductCount();
            Integer productCount = reactionStructureInformation.getProductCount();
            for (int substrateIndex = 0; substrateIndex < eductCount; substrateIndex++) {
                for (int productIndex = 0; productIndex < productCount; productIndex++) {
                    IAtomContainer educt = reactionStructureInformation.getEduct(substrateIndex);
                    IAtomContainer product = reactionStructureInformation.getProduct(productIndex);
                    if ((educt != null && product != null)
                            && (reactionStructureInformation.getEduct(substrateIndex).getAtomCount() > 0
                            && reactionStructureInformation.getProduct(productIndex).getAtomCount() > 0)
                            || mh.getGraphSimilarityMatrix().getValue(substrateIndex, productIndex) == -1) {
                        if (reactionStructureInformation.isEductModified(substrateIndex)
                                || reactionStructureInformation.isProductModified(productIndex)) {

                            Combination c = new Combination(substrateIndex, productIndex);
                            jobReplicatorList.add(c);
                        }
                    }
                }
            }

            if (jobReplicatorList.isEmpty()) {
                return Collections.unmodifiableCollection(mcsSolutions);
            }
            Map<Combination, Set<Combination>> jobMap = new TreeMap<>();

            for (Combination c : jobReplicatorList) {
                int substrateIndex = c.getRowIndex();
                int productIndex = c.getColIndex();
                IAtomContainer educt = reactionStructureInformation.getEduct(substrateIndex);
                IAtomContainer product = reactionStructureInformation.getProduct(productIndex);

                boolean flag = false;
                for (Combination k : jobMap.keySet()) {
                    IAtomContainer eductJob = reactionStructureInformation.getEduct(k.getRowIndex());
                    IAtomContainer productJob = reactionStructureInformation.getProduct(k.getColIndex());

                    if (eductJob == educt
                            && productJob == product) {
                        if (eductJob.getAtomCount() == educt.getAtomCount()
                                && productJob.getAtomCount() == (product.getAtomCount())) {
                            jobMap.get(k).add(c);
                            flag = true;
                            break;
                        }
                    }
                }

                if (!flag) {
                    Set<Combination> set = new TreeSet<>();
                    jobMap.put(c, set);
                }
            }

            /*
             Assign the threads
             */
            int threadsAvailable = Runtime.getRuntime().availableProcessors() - 1;
            if (threadsAvailable == 0) {
                threadsAvailable = 1;
            }

            if (threadsAvailable > jobMap.size()) {
                threadsAvailable = jobMap.size();
            }
            if (DEBUG) {
                System.out.println(threadsAvailable + " threads requested for MCS in " + mh.getTheory());
            }

            if (DEBUG) {
                executor = Executors.newSingleThreadExecutor();
            } else {
                executor = Executors.newCachedThreadPool();
            }
            CompletionService<MCSSolution> callablesQueue = new ExecutorCompletionService<>(executor);

            for (Combination c : jobMap.keySet()) {
                int substrateIndex = c.getRowIndex();
                int productIndex = c.getColIndex();
                IAtomContainer educt = reactionStructureInformation.getEduct(substrateIndex);
                IAtomContainer product = reactionStructureInformation.getProduct(productIndex);

                /*
                 Ring matcher is set trie if both sides have rings else it set to false (IMP for MCS)
                 */
                CycleFinder cycles = Cycles.or(Cycles.all(), Cycles.relevant());
                boolean ring = false;
                boolean ringSizeEqual = false;

                Cycles rings = cycles.find(educt);
                int numberOfCyclesEduct = rings.numberOfCycles();
                rings = cycles.find(product);
                int numberOfCyclesProduct = rings.numberOfCycles();

                if (numberOfCyclesEduct > 0 && numberOfCyclesProduct > 0) {
                    ring = true;
                }

                if (numberOfCyclesEduct == numberOfCyclesProduct) {
                    ringSizeEqual = true;
                }

                if (DEBUG) {
                    System.out.println(educt.getID() + " ED: " + new SmilesGenerator().create(educt));
                    System.out.println(product.getID() + " PD: " + new SmilesGenerator().create(product));
                    System.out.println("----------------------------------");
                }

                MCSThread mcsThread;
                switch (mh.getTheory()) {

                    case MIN:
                        mcsThread = new MCSThread(mh.getTheory(), substrateIndex, productIndex, educt, product, false, ring, true);
                        mcsThread.setHasPerfectRings(ringSizeEqual);
                        mcsThread.setEductCount(eductCount);
                        mcsThread.setProductCount(productCount);
                        break;

                    case MAX:
                        mcsThread = new MCSThread(mh.getTheory(), substrateIndex, productIndex, educt, product, false, ring, true);
                        mcsThread.setHasPerfectRings(ringSizeEqual);
                        mcsThread.setEductCount(eductCount);
                        mcsThread.setProductCount(productCount);
                        break;

                    case MIXTURE:
                        mcsThread = new MCSThread(mh.getTheory(), substrateIndex, productIndex, educt, product, false, ring, false);
                        mcsThread.setHasPerfectRings(ringSizeEqual);
                        mcsThread.setEductCount(eductCount);
                        mcsThread.setProductCount(productCount);
                        break;

                    case RINGS:
                        /*
                         * don't use ring matcher if there are no rings in the molecule
                         * else mappings with be skewed
                         * bond=false;
                         * ring =true;
                         * atom type=true;
                         * Ex: R05219
                         */
                        mcsThread = new MCSThread(mh.getTheory(), substrateIndex, productIndex, educt, product, false, ring, true);
                        mcsThread.setHasPerfectRings(ringSizeEqual);
                        mcsThread.setEductCount(eductCount);
                        mcsThread.setProductCount(productCount);
                        break;

                    default:
                        mcsThread = null;
                        break;
                }
                if (mcsThread != null) {
                    callablesQueue.submit(mcsThread);
                    taskCounter++;
                }
            }

            Collection<MCSSolution> threadedUniqueMCSSolutions = Collections.synchronizedCollection(new ArrayList<MCSSolution>());
            for (int count = 0; count < taskCounter; count++) {
                MCSSolution isomorphism = callablesQueue.take().get();
                threadedUniqueMCSSolutions.add(isomorphism);
            }

//                List<Future<MCSSolution>> invokeAll = executor.invokeAll(callablesQueue);
//                for (Iterator<Future<MCSSolution>> it = invokeAll.iterator(); it.hasNext();) {
//                    Future<MCSSolution> callable = it.next();
//                    MCSSolution isomorphism = callable.get();
//                    if (callable.isDone()) {
//                        mcsSolutions.add(isomorphism);
//                    }
//                }
            // This will make the executor accept no new threads
            // and finish all existing threads in the queue
            executor.shutdown();
            // Wait until all threads are finish
            while (!executor.isTerminated()) {
            }

            if (DEBUG) {
                System.out.println("Gathering MCS solution from the Thread");
            }
            for (MCSSolution mcs : threadedUniqueMCSSolutions) {
                if (mcs == null) {
                    continue;
                }
                int queryPosition = mcs.getQueryPosition();
                int targetPosition = mcs.getTargetPosition();
                if (DEBUG) {
                    System.out.println("MCS " + "i " + queryPosition + " J " + targetPosition + " size " + mcs.getAtomAtomMapping().getCount());
                }

                Combination removeKey = null;
                for (Combination c : jobMap.keySet()) {
                    if (c.getRowIndex() == queryPosition && c.getColIndex() == targetPosition) {
                        removeKey = c;
                        MCSSolution replicatedMCS = replicateMappingOnContainers(mh, c, mcs);
                        mcsSolutions.add(replicatedMCS);
                    }
                }
                if (removeKey != null) {
                    jobMap.remove(removeKey);
                }
            }
            jobReplicatorList.clear();
            System.gc();

        } catch (IOException | CDKException | ExecutionException | InterruptedException | CloneNotSupportedException ex) {
            logger.error(Level.SEVERE, null, ex);
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
        }
        return Collections.unmodifiableCollection(mcsSolutions);
    }

    /**
     *
     * @param mh
     * @param solution
     * @param mcs
     * @return
     */
    static MCSSolution replicateMappingOnContainers(Holder mh, Combination solution, MCSSolution mcs) {
        try {
            ReactionContainer reactionStructureInformation = mh.getReactionContainer();
            IAtomContainer q = reactionStructureInformation.getEduct(solution.getRowIndex());
            IAtomContainer t = reactionStructureInformation.getProduct(solution.getColIndex());

            int diff1 = q.getAtomCount() - mcs.getQueryContainer().getAtomCount();
            int diff2 = t.getAtomCount() - mcs.getTargetContainer().getAtomCount();

            if (DEBUG) {
                if (diff1 != 0 && diff2 != 0) {
                    System.out.println("\n\n " + solution.getRowIndex() + ", Diff in ac1 " + diff1);
                    System.out.println(solution.getColIndex() + ", Diff in ac2 " + diff2);
                    System.out.println("\nac1 " + q.getAtomCount());
                    System.out.println("\nac2 " + t.getAtomCount());

                    System.out.println("\nmac1 " + mcs.getQueryContainer().getAtomCount());
                    System.out.println("\nmac2 " + mcs.getTargetContainer().getAtomCount());
                }
            }

            AtomAtomMapping atomAtomMapping = mcs.getAtomAtomMapping();
            AtomAtomMapping atomAtomMappingNew = new AtomAtomMapping(q, t);
            for (IAtom a : atomAtomMapping.getMappingsByAtoms().keySet()) {
                IAtom atomByID1 = getAtomByID(q, a);
                IAtom b = atomAtomMapping.getMappingsByAtoms().get(a);
                IAtom atomByID2 = getAtomByID(t, b);
                if (DEBUG) {
                    System.out.println("atomByID1 " + atomByID1.getID() + " atomByID2 " + atomByID2.getID());
                }
                if (atomByID1 != null && atomByID2 != null) {
                    atomAtomMappingNew.put(atomByID1, atomByID2);
                } else {
                    logger.error(Level.WARNING, "UnExpected NULL ATOM FOUND");
                }
            }
            return new MCSSolution(solution.getRowIndex(), solution.getColIndex(), q, t, atomAtomMappingNew);
        } catch (IOException | CDKException ex) {
            Logger.getLogger(GraphMatcher.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    private static IAtom getAtomByID(IAtomContainer ac, IAtom atom) {
        if (atom.getID() == null) {
            return null;
        }
        for (IAtom a : ac.atoms()) {
            if (a.getID().equals(atom.getID())) {
                return a;
            }
        }
        return null;
    }

    /**
     *
     * @param educt
     * @param product
     * @param matchesBond bond type matching
     * @param ringMatcher ring matcher
     * @return
     * @throws CDKException
     */
    static boolean isSubgraph(IAtomContainer educt, IAtomContainer product, boolean matchesBond, boolean ringMatcher) throws CDKException {
        if (educt.getAtomCount() <= product.getAtomCount()) {
            Substructure smsd = new Substructure(educt, product, matchesBond, ringMatcher, false, false);
            return smsd.isSubgraph();
        } else {
            Substructure smsd = new Substructure(product, educt, matchesBond, ringMatcher, false, false);
            return smsd.isSubgraph();
        }
    }
}