package com.github.gordonmu.coio

import java.util.*


/**
 * A map where keys consist of a list of sub keys. Keys with the same prefix are stored together in the same subtree.
 */
class PathTreeMap<K : Comparable<K>, V> : MutableMap<List<K>, V>, AbstractMap<List<K>, V>() {

    private val root: NodeImpl = NodeImpl(0, null)

    private var _size: Int = 0

    override val size: Int get() = _size

    override fun containsKey(key: List<K>): Boolean {
        val node = root.getNearest(key)
        return node.depth == key.size && node.value != null
    }

    override fun containsValue(value: V): Boolean {
        return TreeIterator().asSequence().any { it.nodeValue == value }
    }

    override fun get(key: List<K>): V? {
        val node = root.getNearest(key)
        return if (node.depth == key.size)
            node.value
        else
            null
    }

    override fun isEmpty(): Boolean = _size == 0

    override fun clear() {
        root.children?.clear()
        root.children = null
        _size = 0
    }

    override fun put(key: List<K>, value: V): V? {
        require(key.isNotEmpty()) { "Cannot store empty key" }
        val nearest = root.getNearest(key)
        if (nearest.depth == key.size) {
            val h = nearest.toHandle(key)
            val old = h.nodeValue
            h.nodeValue = value
            return old
        }
        var p = nearest
        while (p.depth < key.size - 1) {
            val n = NodeImpl(p.depth + 1, p)
            p.addChild(key, n)
            p = n
        }
        p.addChild(key, NodeImpl(p.depth + 1, p, value))
        _size++
        return null
    }

    override fun putAll(from: Map<out List<K>, V>) {
        from.forEach {
            put(it.key, it.value)
        }
    }

    override fun remove(key: List<K>): V? {
        require(key.isNotEmpty()) { "Cannot remove empty key" }
        val nearest = root.getNearest(key)
        if (nearest.depth != key.size || nearest.value == null)
            return null

        val previous = nearest.value
        if (previous != null) {
            _size--
            nearest.value = null
        }
        if (nearest.children == null)
            nearest.toHandle(key).removeNode()
        return previous
    }

    override fun toString(): String {
        if (isEmpty())
            return "[]"
        val sb = StringBuilder().append('[')
        TreeIterator().asSequence().forEach { nh ->
            nh.nodeValue.let { value ->
                sb.append('/').append(nh.key.joinToString("/")).append(": ").append(value.toString()).append(", ")
            }
        }
        sb.delete(sb.length - 2, sb.length)
        sb.append(']')
        return sb.toString()
    }

    override val entries: MutableSet<MutableMap.MutableEntry<List<K>, V>> = object : MutableSet<MutableMap.MutableEntry<List<K>, V>>, AbstractSet<MutableMap.MutableEntry<List<K>, V>>() {
        override val size: Int = this@PathTreeMap.size
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<List<K>, V>> = TreeIterator(filter = { it.nodeValue != null })
        override fun clear() = this@PathTreeMap.clear()
        override fun isEmpty(): Boolean = this@PathTreeMap.isEmpty()
    }

    interface NodeHandle<K : Comparable<K>, V> {
        /**
         * The key of this node. Can be the empty list if node is root.
         */
        val key: List<K>

        /**
         * The depth of this node in the tree, root is depth 0. Same as key.size.
         */
        val depth: Int

        /**
         * A value assigned to this node, can be null. Consider that nodes with null values are not part of this tree's [Map] methods.
         */
        val nodeValue: V?

        /**
         * Iterate all child nodes in the subtree. This node is always the first result.
         * With maxChildrenDepth of 0, only returns this node.
         * With maxChildrenDepth of 1, returns this node and direct children.
         */
        fun subTree(maxChildrenDepth: Int = Int.MAX_VALUE): Sequence<NodeHandle<K, V>>
    }

    interface MutableNodeHandle<K : Comparable<K>, V> : NodeHandle<K, V> {
        override var nodeValue: V?

        /**
         * Removes this node and the entire subtree.
         */
        fun removeNode()
    }

    fun getNode(key: List<K>): MutableNodeHandle<K, V>? {
        val nearest = getNearestNode(key)
        return if (nearest.key.size == key.size)
            nearest
        else
            null
    }

    /**
     * Returns value for the key with the most matching prefix to the given key.
     */
    fun getNearestNode(key: List<K>): MutableNodeHandle<K, V> {
        val node = root.getNearest(key)
        return node.toHandle(key)
    }

    fun getChildren(key: List<K>): Sequence<NodeHandle<K, V>>? = getNode(key)?.children()

    private inner class NodeImpl(val depth: Int,
                                 val parent: NodeImpl?,
                                 var value: V? = null,
                                 var children: MutableMap<K, NodeImpl>? = null) {

        fun toHandle(key: List<K>): NodeHandleImpl = NodeHandleImpl(key.subList(0, depth), this)

        fun getNearest(key: List<K>): NodeImpl {
            if (key.size <= depth)
                return this
            val pk = key[depth]
            return children?.get(pk)?.getNearest(key) ?: this
        }

        fun removeChild(key: List<K>) {
            children?.remove(key[depth])
            if (children?.isEmpty() == true)
                children = null
            if (children == null && value == null)
                parent?.removeChild(key)
        }

        fun addChild(key: List<K>, child: NodeImpl) {
            val cs = children ?: HashMap<K, NodeImpl>().also {
                children = it
            }
            cs[key[depth]] = child
        }

        override fun toString(): String = "$depth $value (${children?.size ?: 0})"
    }

    private inner class NodeHandleImpl(override val key: List<K>, val node: NodeImpl) : MutableNodeHandle<K, V>, MutableMap.MutableEntry<List<K>, V> {

        override fun toString(): String = "/${key.joinToString(separator = "/")}: ${node.value} (${node.children?.size ?: 0})"

        override var nodeValue: V?
            get() = node.value
            set(value) {
                val previous = node.value
                node.value = value
                if (previous == null && value != null)
                    _size++
                else if (previous != null && value == null) {
                    _size--
                    if (node.children == null)
                        node.parent?.removeChild(key)
                }
            }

        override val value: V get() = node.value!!

        override val depth: Int = key.size

        override fun setValue(newValue: V): V {
            val res = node.value!!
            node.value = newValue
            return res
        }

        override fun removeNode() {
            // need to update the count of values, this is pricy when large trees are cut but should be cheap for leaves
            _size -= subTree().count { it.nodeValue != null }

            // start with the parent
            var p = node.parent
            while (p != null) {
                val pk = key[p.depth]
                // remove the child from the parent using the constant child key
                p.children?.remove(pk)
                // if the children are gone, remove the hashmap to clear memory
                if (p.children?.isEmpty() == true)
                    p.children = null
                // if there are any children or a value then stop here
                if (p.children != null || p.value != null)
                    break
                // otherwise propagate the removal to the parent
                p = p.parent
            }
        }

        override fun subTree(maxChildrenDepth: Int): Sequence<MutableNodeHandle<K, V>> = Sequence { TreeIterator(key, node, maxChildrenDepth) }
    }

    private inner class TreeIterator(baseKey: List<K> = emptyList(),
                                     base: NodeImpl = root,
                                     val maxChildrenDepth: Int = Int.MAX_VALUE,
                                     val filter: (NodeHandleImpl) -> Boolean = { true }) : MutableIterator<NodeHandleImpl> {

        private inner class NodeIterator(val key: List<K>,
                                         var childIter: MutableIterator<MutableMap.MutableEntry<K, NodeImpl>>)

        private val nodeStack = LinkedList<NodeIterator>()
        private var lastHandle: NodeHandleImpl? = null
        private var nextHandle: NodeHandleImpl? = null

        init {
            val baseHandle = base.toHandle(baseKey)
            base.children?.apply {
                nodeStack.push(NodeIterator(baseKey, iterator()))
            }
            nextHandle = if (filter(baseHandle))
                baseHandle
            else
                advanceFiltered()
        }

        override fun hasNext(): Boolean = nextHandle != null

        private fun advance(): NodeHandleImpl? {
            val res: NodeHandleImpl
            while (true) {
                if (nodeStack.isEmpty())
                    return null
                val p = nodeStack.peek()
                if (p.childIter.hasNext().not()) {
                    nodeStack.pop()
                    continue
                }

                val n = p.childIter.next()
                if (n.value.children == null && n.value.value == null)
                    p.childIter.remove()
                else {
                    res = n.value.toHandle(p.key + n.key)
                    break
                }
            }

            // If the next node has children and we are below max depth, push the iterator on the stack
            res.node.children?.apply {
                if (nodeStack.size < maxChildrenDepth)
                    nodeStack.push(NodeIterator(res.key, iterator()))
            }
            return res
        }

        private fun advanceFiltered(): NodeHandleImpl? {
            while (true) {
                val n = advance() ?: return null
                if (filter(n))
                    return n
            }
        }

        override fun next(): NodeHandleImpl {
            val res = nextHandle ?: throw IllegalStateException("iterator has no next")
            lastHandle = res
            nextHandle = advanceFiltered()
            return res
        }

        override fun remove() {
            check(lastHandle != null) { "next has not been called or remove has already been called" }
            lastHandle?.also {
                it.node.value?.apply {
                    // this is the removal of the value
                    _size--
                    it.node.value = null
                    // actually removing it from the tree is a pain, so we just clean in other iterations
                }
            }
            lastHandle = null
        }
    }
}

/**
 * Lists all children nodes of this node.
 */
fun <K : Comparable<K>, V> PathTreeMap.NodeHandle<K, V>.children(): Sequence<PathTreeMap.NodeHandle<K, V>> = subTree(1).drop(1)

/**
 * Lists the full keys for all children of this node.
 */
fun <K : Comparable<K>, V> PathTreeMap.NodeHandle<K, V>.childrenKeys(): Sequence<List<K>> = subTree(1).drop(1).map { it.key }

/**
 * Lists all values of this entire subtree.
 */
fun <K : Comparable<K>, V> PathTreeMap.NodeHandle<K, V>.subTreeValues(): Sequence<V> = subTree().filter { it.nodeValue != null }.map { it.nodeValue!! }