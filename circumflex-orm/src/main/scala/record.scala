package ru.circumflex.orm


import java.sql.PreparedStatement

/**
 * Represents records that could be recovered from relations.
 */
abstract class Record extends JDBCHelper {

  private var fieldsMap: Map[Column[_], Any] = Map()
  private var parentsMap: Map[Association, Any] = Map()
  private var childrenMap: Map[Association, Seq[Any]] = Map()

  def getFieldValue[T](col: Column[T]): Option[T] =
    fieldsMap.get(col).asInstanceOf[Option[T]]

  def setFieldValue[T](col: Column[T], value: T): Unit =
    setFieldValue(col, Some(value))

  def setFieldValue[T](col: Column[T], value: Option[T]) = value match {
    case Some(value) => {
      fieldsMap += (col -> value)
    } case _ => {
      fieldsMap -= col
    }
  }

  def field[T](col: Column[T]) = new Field(this, col)

  def relation: Relation

  def primaryKey: Option[_] = fieldsMap.get(relation.primaryKey.column)

  def isIdentified = primaryKey match {
    case None => false
    case _ => true
  }

  def insert(): Int = {
    val conn = relation.configuration.connectionProvider.getConnection
    val sql = relation.dialect.insertRecord(this)
    sqlLog.debug(sql)
    auto (conn.prepareStatement(sql)) (st => {
      setParams(st, relation.columns)
      return st.executeUpdate
    })
  }

  def update(): Int = {
    val conn = relation.configuration.connectionProvider.getConnection
    val sql = relation.dialect.updateRecord(this)
    sqlLog.debug(sql)
    auto (conn.prepareStatement(sql)) (st => {
      setParams(st, relation.nonPKColumns)
      relation.configuration.typeConverter.write(
        st,
        primaryKey.get,
        relation.nonPKColumns.size + 1)
      return st.executeUpdate
    })
  }

  def save() = if (isIdentified) update()
  else {
    generateFields
    insert()
  }

  def delete(): Int = {
    val conn = relation.configuration.connectionProvider.getConnection
    val sql = relation.dialect.deleteRecord(this)
    sqlLog.debug(sql)
    auto (conn.prepareStatement(sql)) (st => {
      relation.configuration.typeConverter.write(st, primaryKey.get, 1)
      return st.executeUpdate
    })
  }

  private def setParams(st: PreparedStatement, cols: Seq[Column[_]]) =
    (0 until cols.size).foreach(ix => {
      val col = cols(ix)
      val value = this.getFieldValue(col) match {
        case Some (v) => v
        case _ => null
      }
      relation.configuration.typeConverter.write(st, value, ix + 1)
    })

  def generateFields(): Unit =
    relation.columns.flatMap(_.sequence).foreach(seq => {
      val nextval = seq.nextValue
      this.setFieldValue(seq.column, nextval)
    })

}