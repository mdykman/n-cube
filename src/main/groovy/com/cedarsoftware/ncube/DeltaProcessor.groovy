package com.cedarsoftware.ncube

import com.cedarsoftware.ncube.util.LongHashSet
import com.cedarsoftware.util.CaseInsensitiveMap
import com.cedarsoftware.util.CaseInsensitiveSet
import com.cedarsoftware.util.DeepEquals
import com.cedarsoftware.util.StringUtilities
import groovy.transform.CompileStatic
/**
 * This class represents any cell that needs to return content from a URL.
 * For example, String or Binary content.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br/>
 *         Copyright (c) Cedar Software LLC
 *         <br/><br/>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br/><br/>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br/><br/>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
class DeltaProcessor
{
    public static final String DELTA_CELLS = 'delta-cel'
    public static final String DELTA_AXES_COLUMNS = 'delta-col'
    public static final String DELTA_AXES = 'delta-axis'

    public static final String DELTA_COLUMN_ADD = 'col-add'
    public static final String DELTA_COLUMN_REMOVE = 'col-del'
    public static final String DELTA_COLUMN_CHANGE = 'col-upd'
    public static final String DELTA_CELL_REMOVE = 'cell-del'

    public static final String DELTA_AXIS_REF_CHANGE = 'axis-ref-changed'
    public static final String DELTA_AXIS_SORT_CHANGED = 'axis-sort-changed'

    /**
     * Fetch the difference between this cube and the passed in cube.  The two cubes must have the same number of axes
     * with the same names.  If those conditions are met, then this method will return a Map with keys for each delta
     * type.
     *
     * The key DELTA_CELLS, will have an associated value that is a Map<Set<Long>, T> which are the cell contents
     * that are different.  These cell differences when applied to 'this' will result in this cube's cells matching
     * the passed in 'other'. If the value is NCUBE.REMOVE_CELL, then that indicates a cell that needs to be removed.
     * All other cell values are actual cell value changes.
     *
     * The key DELTA_AXES_COLUMNS, contains the column differences.  The value associated to this key is a Map, that
     * maps axis name (case-insensitively) to a Map where the key is a column and the associated value is
     * either the 'true' (new) or false (if it should be removed).
     *
     * In the future, meta-property differences may be reported.
     *
     * @param baseCube NCube considered the original
     * @param changeCube NCube proposing new changes
     * @return Map containing the proposed differences.  If different number of axes, or different axis names,
     * then null is returned.  There are three (3) keys currently in this map:
     * DeltaProcessor.DELTA_AXES (Map of axis name to List<AxisDelta>)
     * DeltaProcessor.DELTA_AXES_COLUMNS (Map of axis name to Map<Column value, ColumnDelta>)
     * DeltaProcessor.DELTA_CELLS (Map of coordinates to Delta description at coordinate).
     */
    static <T> Map<String, Object> getDelta(NCube<T> baseCube, NCube<T> changeCube)
    {
        Map<String, Object> delta = [:]

        if (!baseCube.isComparableCube(changeCube))
        {
            return null
        }

        // Build axis differences
        Map<String, Map<String, Object>> axisDeltaMap = [:] as CaseInsensitiveMap
        delta[DELTA_AXES] = axisDeltaMap

        // Build column differences per axis
        Map<String, Map<Comparable, ColumnDelta>> colDeltaMap = [:] as CaseInsensitiveMap
        delta[DELTA_AXES_COLUMNS] = colDeltaMap

        for (Axis baseAxis : baseCube.axes)
        {
            Axis changeAxis = changeCube.getAxis(baseAxis.name)
            axisDeltaMap[baseAxis.name] = getAxisDelta(baseAxis, changeAxis)
            colDeltaMap[baseAxis.name] = getColumnDelta(baseAxis, changeAxis)
        }

        // Store updates-to-be-made so that if cell equality tests pass, these can be 'played' at the end to
        // transactionally apply the merge.  We do not want a partial merge.
        delta[DELTA_CELLS] = getCellDelta(baseCube, changeCube)
        return delta
    }

    /**
     * Merge the passed in cell change-set into this n-cube.  This will apply all of the cell changes
     * in the passed in change-set to the cells of this n-cube, including adds and removes.
     * @param deltaSet Map containing cell change-set.  The cell change-set contains cell coordinates
     * mapped to the associated value to set (or remove) for the given coordinate.
     */
    static <T> void mergeDeltaSet(NCube<T> target, Map<String, Object> deltaSet)
    {
        // Step 1: Merge axis-level changes
        boolean wasReferenceAxisUpdated = false

        Map<String, Map<String, Object>> axisDeltas = deltaSet[DELTA_AXES] as Map
        axisDeltas.each { k, v ->
            String axisName = k
            Map<String, Object> axisChanges = v

            if (axisChanges.size() > 0)
            {   // There exist changes on the Axis itself, not including possible column changes (sorted, reference, etc)
                Axis axis = target.getAxis(axisName)
                if (axisChanges.containsKey(DELTA_AXIS_SORT_CHANGED))
                {
                    axis.columnOrder = axisChanges[DELTA_AXIS_SORT_CHANGED] as int
                }

                Map<String, Object> ref = axisChanges[DELTA_AXIS_REF_CHANGE] as Map
                if (!ref.isEmpty())
                {   // Merge the reference changes to target cube's axis.
                    Map args = [:]
                    args[ReferenceAxisLoader.REF_TENANT] = ref.ref_tenant
                    args[ReferenceAxisLoader.REF_APP] = ref.ref_app
                    args[ReferenceAxisLoader.REF_VERSION] = ref.ref_version
                    args[ReferenceAxisLoader.REF_STATUS] = ref.ref_status
                    args[ReferenceAxisLoader.REF_BRANCH] = ref.ref_branch
                    args[ReferenceAxisLoader.REF_CUBE_NAME] = ref.ref_cube
                    args[ReferenceAxisLoader.REF_AXIS_NAME] = ref.ref_axis
                    args[ReferenceAxisLoader.TRANSFORM_APP] = ref.tx_app
                    args[ReferenceAxisLoader.TRANSFORM_VERSION] = ref.tx_version
                    args[ReferenceAxisLoader.TRANSFORM_STATUS] = ref.tx_status
                    args[ReferenceAxisLoader.TRANSFORM_BRANCH] = ref.tx_branch
                    args[ReferenceAxisLoader.TRANSFORM_CUBE_NAME] = ref.tx_cube
                    args[ReferenceAxisLoader.TRANSFORM_METHOD_NAME] = ref.tx_method
                    axis.clear()
                    ReferenceAxisLoader refAxisLoader = new ReferenceAxisLoader(target.name, axisName, args)
                    refAxisLoader.load(axis)
                    wasReferenceAxisUpdated = true
                }
            }
        }

        // Step 2: Merge column-level changes
        if (!wasReferenceAxisUpdated)
        {
            Map<String, Map<Long, ColumnDelta>> deltaMap = deltaSet[DELTA_AXES_COLUMNS] as Map
            deltaMap.each { k, v ->
                String axisName = k
                Map<Long, ColumnDelta> colChanges = v

                for (ColumnDelta colDelta : colChanges.values())
                {
                    Column column = colDelta.column
                    Axis axis = target.getAxis(axisName)

                    if (DELTA_COLUMN_ADD == colDelta.changeType)
                    {
                        Comparable value = axis.getValueToLocateColumn(column)
                        Column findCol = axis.findColumn(value)

                        if (findCol == null)
                        {
                            target.addColumn(axisName, column.value, column.columnName, column.id)
                        }
                    }
                    else if (DELTA_COLUMN_REMOVE == colDelta.changeType)
                    {
                        Comparable value = axis.getValueToLocateColumn(column)
                        target.deleteColumn(axisName, value)
                    }
                    else if (DELTA_COLUMN_CHANGE == colDelta.changeType)
                    {
                        target.updateColumn(column.id, column.value)
                    }
                }
            }
        }

        // Step 3: Merge cell-level changes
        Map<Map<String, Object>, T> cellDelta = (Map<Map<String, Object>, T>) deltaSet[DELTA_CELLS]
        // Passed all cell conflict tests, update 'this' cube with the new cells from the other cube (merge)
        cellDelta.each { k, v ->
            Set<Long> cols = deltaCoordToSetOfLong(target, k)
            if (cols != null && cols.size() > 0)
            {
                T value = v
                if (DELTA_CELL_REMOVE == value)
                {   // Remove cell
                    target.removeCellById(cols)
                }
                else
                {   // Add/Update cell
                    target.setCellById(value, cols)
                }
            }
        }

        target.clearSha1()
    }

    /**
     * Test the compatibility of two 'delta change-set' maps.  This method determines if these two
     * change sets intersect properly or intersect with conflicts.  Used internally when merging
     * two ncubes together in branch-merge operations.
     *
     * This code is looking at two change sets (A->Base, B->Base).  A is the delta set between the user's branch
     * n-cube and the n-cube the branch (HEAD(7)) was based on. B is the delta set between current HEAD(10) and Base
     * HEAD(7).  Example:
     * Delta set #1 = User's Branch -> HEAD (7)
     * Delta set #2 = Current HEAD (10) -> HEAD (7)
     * The 'headDelta' is the delta-between another person's branch and HEAD when merging between branches.
     * @param branchDelta Map of cell coordinates to values generated from comparing two cubes (A -> B)
     * @param headDelta Map of cell coordinates to values generated from comparing two cubes (A -> C)
     * @return boolean true if the two cell change-sets are compatible, false otherwise.
     */
    static boolean areDeltaSetsCompatible(Map<String, Object> branchDelta, Map<String, Object> headDelta)
    {
        if (branchDelta == null || headDelta == null)
        {
            return false
        }

        return areAxisDifferencesOK(branchDelta, headDelta) &&
                areAxisColumnDifferencesOK(branchDelta, headDelta) &&
                areCellDifferencesOK(branchDelta, headDelta)
    }

    /**
     * Verify that axis-level changes are OK.
     * @return true if the axis level changes between the two change sets are non-conflicting, false otherwise.
     */
    private static boolean areAxisDifferencesOK(Map<String, Object> branchDelta, Map<String, Object> headDelta)
    {
        Map<String, Object> branchAxisDelta = branchDelta[DELTA_AXES] as Map
        Map<String, Object> headAxisDelta = headDelta[DELTA_AXES] as Map

        if (!ensureAxisNamesAndCountSame(branchAxisDelta.keySet(), headAxisDelta.keySet()))
        {
            return false
        }

        // Column change maps must be compatible
        for (Map.Entry<String, Object> entry1 : branchAxisDelta.entrySet())
        {
            // Note: Not checking for possible (and noted) SORT difference, as that is always a compatible change.
            String axisName = entry1.key
            Map<String, Object> branchRef = branchAxisDelta[axisName][DELTA_AXIS_REF_CHANGE] as Map
            Map<String, Object> headRef = headAxisDelta[axisName][DELTA_AXIS_REF_CHANGE] as Map

            if (branchRef.isEmpty() && headRef.isEmpty())
            {   // OK - skip, neither side's axis is a reference axis
            }
            else if (branchRef.isEmpty() && !headRef.isEmpty())
            {   // conflict - user broke references
                return false
            }
            else if (!branchRef.isEmpty() && headRef.isEmpty())
            {   // conflict - user converted axis to reference
                return false
            }
            else // if (!branchRef.isEmpty() && !headRef.isEmpty())
            {   // Both are reference axes
                String bTenant = branchRef.ref_tenant
                String hTenant = headRef.ref_tenant
                String bApp = branchRef.ref_app
                String hApp = headRef.ref_app
                String bVer = branchRef.ref_version
                String hVer = headRef.ref_version
                String bStatus = branchRef.ref_status
                String hStatus = headRef.ref_status
                String bBranch = branchRef.ref_branch
                String hBranch = headRef.ref_branch
                String bCube = branchRef.ref_cube
                String hCube = headRef.ref_cube
                String bAxis = branchRef.ref_axis
                String hAxis = headRef.ref_axis

                if (StringUtilities.equalsIgnoreCase(bTenant, hTenant) &&
                        StringUtilities.equalsIgnoreCase(bApp, hApp) &&
                        ApplicationID.getVersionValue(bVer) >= ApplicationID.getVersionValue(hVer) &&
                        StringUtilities.equalsIgnoreCase(bStatus, hStatus) &&
                        StringUtilities.equalsIgnoreCase(bBranch, hBranch) &&
                        StringUtilities.equalsIgnoreCase(bCube, hCube) &&
                        StringUtilities.equalsIgnoreCase(bAxis, hAxis))
                {
                    // OK
                }
                else
                {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Verify that axis-Column changes are OK.
     * @return true if the axis column changes between the two change sets are non-conflicting, false otherwise.
     */
    private static boolean areAxisColumnDifferencesOK(Map<String, Object> branchDelta, Map<String, Object> headDelta)
    {
        Map<String, Map<Comparable, ColumnDelta>> deltaMap1 = branchDelta[DELTA_AXES_COLUMNS] as Map
        Map<String, Map<Comparable, ColumnDelta>> deltaMap2 = headDelta[DELTA_AXES_COLUMNS] as Map

        if (!ensureAxisNamesAndCountSame(deltaMap1.keySet(), deltaMap2.keySet()))
        {
            return false
        }

        // Column change maps must be compatible
        for (Map.Entry<String, Map<Comparable, ColumnDelta>> entry1 : deltaMap1.entrySet())
        {
            String axisName = entry1.key
            // Comparable key in Map below = locatorKey (rule name, rule ID, or valueThatMatches for other Axis Types)
            Map<Comparable, ColumnDelta> changes1 = entry1.value
            Map<Comparable, ColumnDelta> changes2 = deltaMap2[axisName]

            for (Map.Entry<Comparable, ColumnDelta> colEntry1 : changes1.entrySet())
            {
                ColumnDelta delta1 = colEntry1.value
                ColumnDelta delta2 = changes2[delta1.locatorKey]

                if (delta2 == null)
                    continue   // no column changed with same ID, delta1 is OK

                if (delta2.axisType != delta1.axisType)
                    return false   // different axis types

                if (delta1.column.value != delta2.column.value)
                    return false   // value is different for column with same ID

                if (delta1.changeType != delta2.changeType)
                    return false   // different change type (REMOVE vs ADD, CHANGE vs REMOVE, etc.)
            }
        }
        return true
    }

    /**
     * Verify that cell changes are OK between the two change sets.
     * @return true if the cell changes between the two change sets are non-conflicting, false otherwise.
     */
    private static boolean areCellDifferencesOK(Map<String, Object> branchDelta, Map<String, Object> headDelta)
    {
        Map<Map<String, Object>, Object> delta1 = branchDelta[DELTA_CELLS] as Map
        Map<Map<String, Object>, Object> delta2 = headDelta[DELTA_CELLS] as Map
        Map<Map<String, Object>, Object> smallerChangeSet
        Map<Map<String, Object>, Object> biggerChangeSet

        // Performance optimization: determine which cell change set is smaller.
        if (delta1.size() < delta2.size())
        {
            smallerChangeSet = delta1
            biggerChangeSet = delta2
        }
        else
        {
            smallerChangeSet = delta2
            biggerChangeSet = delta1
        }

        for (Map.Entry<Map<String, Object>, Object> entry : smallerChangeSet.entrySet())
        {
            Map<String, Object> deltaCoord = entry.key

            if (biggerChangeSet.containsKey(deltaCoord))
            {
                CellInfo info1 = new CellInfo(entry.value)
                CellInfo info2 = new CellInfo(biggerChangeSet[deltaCoord])

                if (info1 != info2)
                {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Gather difference between two axis as pertaining only to the Axis properties itself, not
     * the associated columns.
     */
    private static Map<String, Object> getAxisDelta(Axis baseAxis, Axis changeAxis)
    {
        Map<String, Object> axisDeltas = [:]

        if (baseAxis.getColumnOrder() != changeAxis.getColumnOrder())
        {   // If column order changed, set the new column order
            axisDeltas[DELTA_AXIS_SORT_CHANGED] = changeAxis.getColumnOrder()
        }

        ApplicationID changeApp = changeAxis.getReferencedApp()
        ApplicationID changeTxApp = changeAxis.getTransformApp()
        Map ref = [:]
        axisDeltas[DELTA_AXIS_REF_CHANGE] = ref

        if (changeAxis.isReference())
        {   // See if reference is updated

            ref.ref_tenant = changeApp.tenant
            ref.ref_app = changeApp.app
            ref.ref_version = changeApp.version
            ref.ref_status = changeApp.status
            ref.ref_branch = changeApp.branch
            ref.ref_cube = changeAxis.referenceCubeName
            ref.ref_axis = changeAxis.referenceAxisName

            if (changeAxis.isReferenceTransformed())
            {
                ref.tx_app = changeTxApp.app
                ref.tx_version = changeTxApp.version
                ref.tx_status = changeTxApp.status
                ref.tx_branch = changeTxApp.branch
                ref.tx_cube = changeAxis.transformCubeName
                ref.tx_method = changeAxis.transformMethodName
            }
            else
            {
                ref.tx_app = null
                ref.tx_version = null
                ref.tx_status = null
                ref.tx_branch = null
                ref.tx_cube = null
                ref.tx_method = null
            }
        }

        if (baseAxis.isReference() && !changeAxis.isReference())
        {   // Break reference being merged
            ref.ref_tenant = null
            ref.ref_app = null
            ref.ref_version = null
            ref.ref_status = null
            ref.ref_branch = null
            ref.ref_cube = null
            ref.ref_axis = null

            ref.tx_app = null
            ref.tx_version = null
            ref.tx_status = null
            ref.tx_branch = null
            ref.tx_cube = null
            ref.tx_method = null
        }

        return axisDeltas
    }

    /**
     * Ensure that the two passed in Maps hav the same number of axes, and that the names are the same,
     * case-insensitive.
     * @return true if the key sets are compatible, false otherwise.
     */
    private static boolean ensureAxisNamesAndCountSame(Set<String> axisNames1, Set<String> axisNames2)
    {
        if (axisNames1.size() != axisNames2.size())
        {   // Must have same number of axis (axis name is the outer Map key).
            return false
        }

        Set<String> a1 = new CaseInsensitiveSet<>(axisNames1)
        Set<String> a2 = new CaseInsensitiveSet<>(axisNames2)
        a1.removeAll(a2)

        if (!a1.isEmpty())
        {   // Axis names must be all be the same (ignoring case)
            return false
        }
        return true
    }

    /**
     * Gather the differences between the columns on the two passed in Axes.
     */
    private static Map<Comparable, ColumnDelta> getColumnDelta(Axis baseAxis, Axis changeAxis)
    {
        Map<Comparable, ColumnDelta> deltaColumns = new CaseInsensitiveMap<>()
        Map<Comparable, Column> copyColumns = new LinkedHashMap<>()

        for (Column baseColumn : baseAxis.columns)
        {
            Comparable locatorKey = baseAxis.getValueToLocateColumn(baseColumn)
            copyColumns[locatorKey] = baseColumn
        }

        for (Column changeColumn : changeAxis.columns)
        {
            Comparable locatorKey = changeAxis.getValueToLocateColumn(changeColumn)
            Column foundCol = baseAxis.findColumn(locatorKey)

            if (foundCol == null)
            {
                deltaColumns[locatorKey] = new ColumnDelta(baseAxis.getType(), changeColumn, locatorKey, DELTA_COLUMN_ADD)
            }
            else if (foundCol.value != changeColumn.value)
            {
                deltaColumns[locatorKey] = new ColumnDelta(baseAxis.getType(), changeColumn, locatorKey, DELTA_COLUMN_CHANGE)
                copyColumns.remove(locatorKey)
            }
            else
            {   // Matched - this column will not be added to the delta map.
                copyColumns.remove(locatorKey)
            }
        }

        // Columns left over - these are columns 'this' axis has that the 'other' axis does not have.
        for (Column column : copyColumns.values())
        {   // If 'this' axis has columns 'other' axis does not, then mark these to be removed (like we do with cells).
            Comparable locatorKey = changeAxis.getValueToLocateColumn(column)
            deltaColumns[locatorKey] = new ColumnDelta(baseAxis.getType(), column, locatorKey, DELTA_COLUMN_REMOVE)
        }

        return deltaColumns
    }

    /**
     * Get all cellular differences between two n-cubes.
     * @param other NCube from which to generate the delta.
     * @return Map containing a Map of cell coordinates [key is Map<String, Object> and value (T)].
     */
    private static <T> Map<Map<String, Object>, T> getCellDelta(NCube<T> thisCube, NCube<T> other)
    {
        Map<Map<String, Object>, T> delta = new HashMap<>()
        Set<Map<String, Object>> copyCells = new HashSet<>()

        thisCube.cellMap.each { key, value ->
            copyCells.add(thisCube.getCoordinateFromIds(key))
        }

        // At this point, the cubes have the same number of axes and same axis types.
        // Now, compute cell deltas.
        other.cellMap.each { key, value ->
            Map<String, Object> deltaCoord = other.getCoordinateFromIds(key)
            Set<Long> idKey = deltaCoordToSetOfLong(other, deltaCoord)
            if (idKey != null)
            {   // Was able to bind deltaCoord between cubes
                T content = thisCube.getCellByIdNoExecute(idKey)
                T otherContent = value
                copyCells.remove(deltaCoord)

                CellInfo info = new CellInfo(content)
                CellInfo otherInfo = new CellInfo(otherContent)

                if (info != otherInfo)
                {
                    delta[deltaCoord] = otherContent
                }
            }
        }

        for (Map<String, Object> coord : copyCells)
        {
            delta[coord] = (T) DELTA_CELL_REMOVE
        }

        return delta
    }

    /**
     * Return a list of Delta objects describing the differences between two n-cubes.
     * @param other NCube to compare 'this' n-cube to
     * @return List<Delta> object.  The Delta class contains a Location (loc) which describes the
     * part of an n-cube that differs (ncube, axis, column, or cell) and the Type (type) of difference
     * (ADD, UPDATE, or DELETE).  Finally, it includes an English description of the difference as well.
     */
    public static List<Delta> getDeltaDescription(NCube thisCube, NCube other)
    {
        List<Delta> changes = []

        if (!thisCube.name.equalsIgnoreCase(other.name))
        {
            String s = "Name changed from '" + other.name + "' to '" + thisCube.name + "'"
            changes.add(new Delta(Delta.Location.NCUBE, Delta.Type.UPDATE, s))
        }

        List<Delta> metaChanges = compareMetaProperties(other.metaProperties, thisCube.metaProperties, Delta.Location.NCUBE_META, "n-cube '" + thisCube.name + "'")
        changes.addAll(metaChanges)

        Set<String> a1 = thisCube.getAxisNames()
        Set<String> a2 = other.getAxisNames()
        a1.removeAll(a2)

        boolean axesChanged = false
        if (!a1.isEmpty())
        {
            String s = "Added axis: " + a1
            changes.add(new Delta(Delta.Location.AXIS, Delta.Type.ADD, s))
            axesChanged = true
        }

        a1 = thisCube.getAxisNames()
        a2.removeAll(a1)
        if (!a2.isEmpty())
        {
            String s = "Removed axis: " + a2
            changes.add(new Delta(Delta.Location.AXIS, Delta.Type.DELETE, s))
            axesChanged = true
        }

        for (Axis newAxis : thisCube.axes)
        {
            Axis oldAxis = other.getAxis(newAxis.name)
            if (oldAxis == null)
            {
                continue
            }
            if (!newAxis.areAxisPropsEqual(oldAxis))
            {
                String s = "Axis properties changed from " + oldAxis.getAxisPropString() + " to " + newAxis.getAxisPropString()
                changes.add(new Delta(Delta.Location.AXIS, Delta.Type.UPDATE, s))
            }

            metaChanges = compareMetaProperties(oldAxis.metaProperties, newAxis.metaProperties, Delta.Location.AXIS_META, "axis: " + newAxis.name)
            changes.addAll(metaChanges)

            for (Column newCol : newAxis.columns)
            {
                Column oldCol = oldAxis.getColumnById(newCol.id)
                if (oldCol == null)
                {
                    String s = "Column: " + newCol.value + " added to axis: " + newAxis.name
                    changes.add(new Delta(Delta.Location.COLUMN, Delta.Type.ADD, s))
                }
                else
                {   // Check Column meta properties
                    metaChanges = compareMetaProperties(oldCol.metaProperties, newCol.metaProperties, Delta.Location.COLUMN_META, "column '" + newAxis.name + "'")
                    changes.addAll(metaChanges)

                    if (!DeepEquals.deepEquals(oldCol.value, newCol.value))
                    {
                        String s = "Column value changed from: " + oldCol.value + " to: " + newCol.value
                        changes.add(new Delta(Delta.Location.COLUMN, Delta.Type.UPDATE, s))
                    }
                }
            }

            for (Column oldCol : oldAxis.columns)
            {
                Column newCol = newAxis.getColumnById(oldCol.id)
                if (newCol == null)
                {
                    String s = "Column: " + oldCol.value + " removed"
                    changes.add(new Delta(Delta.Location.COLUMN, Delta.Type.DELETE, s))
                }
            }
        }

        // Different dimensionality, don't compare cells
        if (axesChanged)
        {
            return changes
        }

        thisCube.cellMap.each { key, value ->
            LongHashSet newCellKey = (LongHashSet) key
            Object newCellValue = value

            if (other.cellMap.containsKey(newCellKey))
            {
                Object oldCellValue = other.cellMap[newCellKey]
                if (!DeepEquals.deepEquals(newCellValue, oldCellValue))
                {
                    Map<String, Object> properCoord = thisCube.getDisplayCoordinateFromIds(newCellKey)
                    String s = "Cell changed at location: " + properCoord + ", from: " +
                            (oldCellValue == null ? null : oldCellValue.toString()) + ", to: " +
                            (newCellValue == null ? null : newCellValue.toString())
                    changes.add(new Delta(Delta.Location.CELL, Delta.Type.UPDATE, s))
                }
            }
            else
            {
                Map<String, Object> properCoord = thisCube.getDisplayCoordinateFromIds(newCellKey)
                String s = "Cell added at location: " + properCoord + ", value: " + (newCellValue == null ? null : newCellValue.toString())
                changes.add(new Delta(Delta.Location.CELL, Delta.Type.ADD, s))
            }
        }

        other.cellMap.each { key, value ->
            LongHashSet oldCellKey = (LongHashSet) key

            if (!thisCube.cellMap.containsKey(oldCellKey))
            {
                boolean allColsStillExist = true
                for (Long colId : oldCellKey)
                {
                    Axis axis = thisCube.getAxisFromColumnId(colId)
                    if (axis == null)
                    {
                        allColsStillExist = false
                        break
                    }
                }

                // Make sure all columns for this cell still exist before reporting it as removed.  Otherwise, a
                // dropped column would report a ton of removed cells.
                if (allColsStillExist)
                {
                    Map<String, Object> properCoord = thisCube.getDisplayCoordinateFromIds(oldCellKey)
                    String s = "Cell removed at location: " + properCoord + ", value: " + (value == null ? null : value.toString())
                    changes.add(new Delta(Delta.Location.CELL, Delta.Type.DELETE, s))
                }
            }
        }
        return changes
    }

    /**
     * Build List of Delta objects describing the difference between the two passed in Meta-Properties Maps.
     */
    protected static List<Delta> compareMetaProperties(Map<String, Object> oldMeta, Map<String, Object> newMeta, Delta.Location location, String locName)
    {
        List<Delta> changes = new ArrayList<>()
        Set<String> oldKeys = new CaseInsensitiveSet<>(oldMeta.keySet())
        Set<String> sameKeys = new CaseInsensitiveSet<>(newMeta.keySet())
        sameKeys.retainAll(oldKeys)

        Set<String> addedKeys  = new CaseInsensitiveSet<>(newMeta.keySet())
        addedKeys.removeAll(sameKeys)
        if (!addedKeys.isEmpty())
        {
            StringBuilder s = makeMap(newMeta, addedKeys)
            String entry = addedKeys.size() > 1 ? "meta-entries" : "meta-entry"
            changes.add(new Delta(location, Delta.Type.ADD, locName + " " + entry + " added: " + s))
        }

        Set<String> deletedKeys  = new CaseInsensitiveSet<>(oldMeta.keySet())
        deletedKeys.removeAll(sameKeys)
        if (!deletedKeys.isEmpty())
        {
            StringBuilder s = makeMap(oldMeta, deletedKeys)
            String entry = deletedKeys.size() > 1 ? "meta-entries" : "meta-entry"
            changes.add(new Delta(location, Delta.Type.DELETE, locName + " " + entry + " deleted: " + s))
        }

        int i = 0
        StringBuilder s = new StringBuilder()
        for (String key : sameKeys)
        {
            if (!DeepEquals.deepEquals(oldMeta[key], newMeta[key]))
            {
                s.append(key).append("->").append(oldMeta[key]).append(" ==> ").append(key).append("->").append(newMeta[key]).append(", ")
                i++
            }
        }
        if (i > 0)
        {
            s.setLength(s.length() - 2)     // remove extra ", " at end
            String entry = i > 1 ? "meta-entries" : "meta-entry"
            changes.add(new Delta(location, Delta.Type.UPDATE, locName + " " + entry + " changed: " + s))
        }

        return changes
    }

    /**
     * Convert a DeltaCoord to a Set<Long>.  A 'deltaCoord' is a coordinate which has String axis name
     * keys and associated values (to match against standard axes), but for Rule axes it has the Long ID
     * for the associated value.  These deltaCoord's are used during cube merging to allow coordinates from
     * one cube to bind into another cube.
     * @param deltaCoord Map<String, Object> where the String keys are axis names, and the object is the associated
     * value to bind to the axis.  For RULE axes, the associated value is a Long ID (or rule name, or null
     * for default column - note: long ID can be used for default too).
     * @return Set<Long> that can be used with any n-cube API that binds by ID (getCellById, etc.) or null
     * if the deltaCoord could not bind to this n-cube.
     */
    private static <T> Set<Long> deltaCoordToSetOfLong(NCube<T> target, final Map<String, Object> deltaCoord)
    {
        final Set<Long> key = new LongHashSet()

        for (final Axis axis : target.axes)
        {
            final Object value = deltaCoord[axis.name]
            final Column column = axis.findColumn((Comparable) value)
            if (column == null)
            {
                return null
            }
            key.add(column.id)
        }
        return key
    }

    private static StringBuilder makeMap(Map<String, Object> newMeta, Set<String> addedKeys)
    {
        StringBuilder s = new StringBuilder()
        Iterator<String> i = addedKeys.iterator()
        while (i.hasNext())
        {
            String key = i.next()
            s.append(key)
            s.append('->')
            s.append(newMeta[key])
            if (i.hasNext())
            {
                s.append(', ')
            }
        }
        return s
    }
}
