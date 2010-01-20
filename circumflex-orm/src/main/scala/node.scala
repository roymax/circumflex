/*
 * Copyright (C) 2009-2010 Boris Okunskiy (http://incarnate.ru)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

package ru.circumflex.orm

import ORM._

/**
 * Wraps relational nodes (tables, views, virtual tables, subqueries and other stuff)
 * with an alias so that they may appear within SQL FROM clause.
 */
abstract class RelationNode[R](val relation: Relation[R])
        extends Relation[R] with SQLable {

  protected var _alias = "this"

  override def recordClass = relation.recordClass

  /**
   * Returns an alias of this node, which is used in SQL statements.
   * "this" alias has special meaning: when used in query it is appended with
   * query-unique alias counter value.
   */
  def alias = _alias

  /**
   * Just proxies relation's primary key.
   */
  def primaryKey = relation.primaryKey

  /**
   * Creates a record projection.
   */
  def * = new RecordProjection[R](this)

  /**
   * One or more projections that correspond to this node.
   */
  def projections: Seq[Projection[_]] = List(*)

  /**
   * Returns columns of underlying relation.
   */
  override def columns = relation.columns

  /**
   * Returns associations defined on underlying relation.
   */
  override def associations = relation.associations

  /**
   * Retrieves an association path by delegating calls to underlying relations.
   */
  override def getParentAssociation[P](parent: Relation[P]): Option[Association[R, P]] =
    parent match {
      case parentNode: RelationNode[P] => getParentAssociation(parentNode.relation)
      case _ => relation match {
        case childNode: RelationNode[R] => childNode.relation.getParentAssociation(parent)
        case _ => relation.getParentAssociation(parent)
      }
    }

  /**
   * Proxies relation's name.
   */
  override def relationName = relation.relationName

  /**
   * Creates a join with specified parent node using specified association.
   */
  def join[J](node: RelationNode[J], association: Association[R, J]): ChildToParentJoin[R, J] =
    new ChildToParentJoin(this, node, association)

  /**
   * Creates a join with specified child node using specified association.
   */
  def join[J](node: RelationNode[J], association: Association[J, R]): ParentToChildJoin[R, J] =
    new ParentToChildJoin(this, node, association)

  /**
   * Tries to create either type of join depending on inferred association.
   */
  def join[J](node: RelationNode[J]): JoinNode[R, J] = getParentAssociation(node) match {
    case Some(a) =>              // this is child; node is parent
      new ChildToParentJoin(this, node, a.asInstanceOf[Association[R, J]])
    case None => getChildAssociation(node) match {
      case Some(a) =>            // this is parent; node is child
        new ParentToChildJoin(this, node, a.asInstanceOf[Association[J, R]])
      case None =>
        throw new ORMException("Failed to join " + this + " with " + node + ": no associations found.")
    }
  }

  /**
   * Reassigns an alias for this node.
   */
  def as(alias: String): this.type = {
    this._alias = alias
    return this
  }

  /**
   * Creates a field projection with default alias.
   */
  def projection[T](col: Column[T, R]): ColumnProjection[T, R] =
    new ColumnProjection(this, col)

  override def hashCode = relation.hashCode
  override def equals(obj: Any) = obj match {
    case r: RelationNode[_] => equals(r.relation)
    case r: Relation[_] => this.relation == r
    case _ => false
  }
}

class TableNode[R](val table: Table[R])
        extends RelationNode[R](table) {

  /**
   * Dialect should return qualified name with alias (e.g. "myschema.mytable as myalias")
   */
  def toSql = dialect.tableAlias(table, alias)

}

class ViewNode[R](val view: View[R])
        extends RelationNode[R](view) {

  /**
   * Dialect should return qualified name with alias (e.g. "myschema.mytable as myalias")
   */
  def toSql = dialect.viewAlias(view, alias)

}

abstract class JoinType(val sql: String)
object InnerJoin extends JoinType(dialect.innerJoin)
object LeftJoin extends JoinType(dialect.leftJoin)
object RightJoin extends JoinType(dialect.rightJoin)
object FullJoin extends JoinType(dialect.fullJoin)

/**
 * Represents a join node between parent and child relation.
 */
abstract class JoinNode[L, R](protected var _left: RelationNode[L],
                              protected var _right: RelationNode[R],
                              protected var _joinType: JoinType,
                              protected var _on: String)
        extends RelationNode[L](_left) {

  private var _auxiliaryConditions: Seq[String] = Nil

  def left = _left
  def right = _right
  def joinType = _joinType

  override def alias = left.alias

  def auxiliaryConditions = _auxiliaryConditions

  /**
   * Adds an auxiliary condition to this join.
   */
  def on(condition: String): this.type = {
    _auxiliaryConditions ++= List(condition)
    return this
  }

  def on = "on (" + _on + {
    if (_auxiliaryConditions.size > 0)
      " and " + _auxiliaryConditions.mkString(" and ")
    else ""
  } + ")"

  /**
   * Join nodes return parent node's projections joined with child node's ones.
   */
  override def projections = left.projections ++ right.projections

  def replaceLeft(newLeft: RelationNode[L]): this.type = {
    this._left = newLeft
    return this
  }

  def replaceRight(newRight: RelationNode[R]): this.type = {
    this._right = newRight
    return this
  }

  /**
   * Dialect should return properly joined parent and child nodes.
   */
  def toSql = dialect.join(this)
}

class ChildToParentJoin[L, R](childNode: RelationNode[L],
                              parentNode: RelationNode[R],
                              val association: Association[L, R])
        extends JoinNode[L, R](
          childNode,
          parentNode,
          LeftJoin,
          childNode.alias + "." + association.childColumn.columnName + "=" +
                  parentNode.alias + "." + association.parentColumn.columnName
          )

class ParentToChildJoin[L, R](parentNode: RelationNode[L],
                              childNode: RelationNode[R],
                              val association: Association[R, L])
        extends JoinNode[L, R](
          parentNode,
          childNode,
          LeftJoin,
          parentNode.alias + "." + association.parentColumn.columnName + "=" +
                  childNode.alias + "." + association.childColumn.columnName
          )