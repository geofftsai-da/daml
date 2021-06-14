// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine

import com.daml.lf.data.Ref._
import com.daml.lf.data.{BackStack, ImmArray, ImmArrayCons}
import com.daml.lf.language.Ast._
import com.daml.lf.speedy.SResult.SVisibleByKey
import com.daml.lf.transaction.GlobalKeyWithMaintainers
import com.daml.lf.value.Value._
import scalaz.Monad

import scala.annotation.tailrec

/** many operations require to look up packages and contracts. we do this
  * by allowing our functions to pause and resume after the contract has been
  * fetched.
  */
sealed trait Result[+A] extends Product with Serializable {
  def map[B](f: A => B): Result[B] = this match {
    case ResultDone(x) => ResultDone(f(x))
    case ResultError(err) => ResultError(err)
    case ResultNeedContract(acoid, resume) =>
      ResultNeedContract(acoid, mbContract => resume(mbContract).map(f))
    case ResultNeedPackage(pkgId, resume) =>
      ResultNeedPackage(pkgId, mbPkg => resume(mbPkg).map(f))
    case ResultNeedKey(gk, resume) =>
      ResultNeedKey(gk, mbAcoid => resume(mbAcoid).map(f))
    case ResultNeedLocalKeyVisible(stakeholders, resume) =>
      ResultNeedLocalKeyVisible(stakeholders, visible => resume(visible).map(f))
  }

  def flatMap[B](f: A => Result[B]): Result[B] = this match {
    case ResultDone(x) => f(x)
    case ResultError(err) => ResultError(err)
    case ResultNeedContract(acoid, resume) =>
      ResultNeedContract(acoid, mbContract => resume(mbContract).flatMap(f))
    case ResultNeedPackage(pkgId, resume) =>
      ResultNeedPackage(pkgId, mbPkg => resume(mbPkg).flatMap(f))
    case ResultNeedKey(gk, resume) =>
      ResultNeedKey(gk, mbAcoid => resume(mbAcoid).flatMap(f))
    case ResultNeedLocalKeyVisible(stakeholders, resume) =>
      ResultNeedLocalKeyVisible(stakeholders, visible => resume(visible).flatMap(f))
  }

  def consume(
      pcs: ContractId => Option[ContractInst[VersionedValue[ContractId]]],
      packages: PackageId => Option[Package],
      keys: GlobalKeyWithMaintainers => Option[ContractId],
      localKeyVisible: Set[Party] => VisibleByKey,
  ): Either[Error, A] = {
    @tailrec
    def go(res: Result[A]): Either[Error, A] =
      res match {
        case ResultDone(x) => Right(x)
        case ResultError(err) => Left(err)
        case ResultNeedContract(acoid, resume) => go(resume(pcs(acoid)))
        case ResultNeedPackage(pkgId, resume) => go(resume(packages(pkgId)))
        case ResultNeedKey(key, resume) => go(resume(keys(key)))
        case ResultNeedLocalKeyVisible(stakeholders, resume) =>
          go(resume(localKeyVisible(stakeholders)))
      }
    go(this)
  }
}

final case class ResultDone[A](result: A) extends Result[A]
object ResultDone {
  val Unit: ResultDone[Unit] = new ResultDone(())
}
final case class ResultError(err: Error) extends Result[Nothing]
object ResultError {
  def apply(packageError: Error.Package.Error): ResultError =
    ResultError(Error.Package(packageError))
  def apply(preprocessingError: Error.Preprocessing.Error): ResultError =
    ResultError(Error.Preprocessing(preprocessingError))
  def apply(interpretationError: Error.Interpretation.Error): ResultError =
    ResultError(Error.Interpretation(interpretationError))
  def apply(validationError: Error.Validation.Error): ResultError =
    ResultError(Error.Validation(validationError))
}

/** Intermediate result indicating that a [[ContractInst]] is required to complete the computation.
  * To resume the computation, the caller must invoke `resume` with the following argument:
  * <ul>
  * <li>`Some(contractInstance)`, if the caller can dereference `acoid` to `contractInstance`</li>
  * <li>`None`, if the caller is unable to dereference `acoid`
  * </ul>
  */
final case class ResultNeedContract[A](
    acoid: ContractId,
    resume: Option[ContractInst[VersionedValue[ContractId]]] => Result[A],
) extends Result[A]

/** Intermediate result indicating that a [[Package]] is required to complete the computation.
  * To resume the computation, the caller must invoke `resume` with the following argument:
  * <ul>
  * <li>`Some(package)`, if the caller can dereference `packageId` to `package`</li>
  * <li>`None`, if the caller is unable to dereference `packageId`
  * </ul>
  */
final case class ResultNeedPackage[A](packageId: PackageId, resume: Option[Package] => Result[A])
    extends Result[A]

final case class ResultNeedKey[A](
    key: GlobalKeyWithMaintainers,
    resume: Option[ContractId] => Result[A],
) extends Result[A]

/** Whether a given contract can be fetched by key, i.e., actAs union readAs
  *    contains at least one stakeholder.
  */
sealed trait VisibleByKey {
  private[engine] def toSVisibleByKey: SVisibleByKey
}
object VisibleByKey {

  /** Contract is not visible, includes actAs and readAs for error reporting
    */
  final case class NotVisible(actAs: Set[Party], readAs: Set[Party]) extends VisibleByKey {
    override def toSVisibleByKey = SVisibleByKey.NotVisible(actAs, readAs)
  }
  final case object Visible extends VisibleByKey {
    override val toSVisibleByKey = SVisibleByKey.Visible
  }

  def fromSubmitters(
      actAs: Set[Party],
      readAs: Set[Party] = Set.empty,
  ): Set[Party] => VisibleByKey = {
    val readers = actAs union readAs
    stakeholders =>
      if (readers.intersect(stakeholders).nonEmpty) {
        VisibleByKey.Visible
      } else {
        VisibleByKey.NotVisible(actAs, readAs)
      }
  }
}

/** Check that a local contract with the given stakeholders
  *    can be fetched by key.
  */
final case class ResultNeedLocalKeyVisible[A](
    stakeholders: Set[Party],
    resume: VisibleByKey => Result[A],
) extends Result[A]

object Result {
  // fails with ResultError if the package is not found
  private[lf] def needPackage[A](packageId: PackageId, resume: Package => Result[A]) =
    ResultNeedPackage(
      packageId,
      {
        case None => ResultError(Error.Interpretation.Generic(s"Couldn't find package $packageId"))
        case Some(pkg) => resume(pkg)
      },
    )

  private[lf] def needContract[A](
      acoid: ContractId,
      resume: ContractInst[VersionedValue[ContractId]] => Result[A],
  ) =
    ResultNeedContract(
      acoid,
      {
        case None => ResultError(Error.Interpretation.ContractNotFound(acoid))
        case Some(contract) => resume(contract)
      },
    )

  def sequence[A](results0: ImmArray[Result[A]]): Result[ImmArray[A]] = {
    @tailrec
    def go(okResults: BackStack[A], results: ImmArray[Result[A]]): Result[BackStack[A]] =
      results match {
        case ImmArray() => ResultDone(okResults)
        case ImmArrayCons(res, results_) =>
          res match {
            case ResultDone(x) => go(okResults :+ x, results_)
            case ResultError(err) => ResultError(err)
            case ResultNeedPackage(packageId, resume) =>
              ResultNeedPackage(
                packageId,
                pkg =>
                  resume(pkg).flatMap(x =>
                    Result
                      .sequence(results_)
                      .map(otherResults => (okResults :+ x) :++ otherResults)
                  ),
              )
            case ResultNeedContract(acoid, resume) =>
              ResultNeedContract(
                acoid,
                coinst =>
                  resume(coinst).flatMap(x =>
                    Result
                      .sequence(results_)
                      .map(otherResults => (okResults :+ x) :++ otherResults)
                  ),
              )
            case ResultNeedKey(gk, resume) =>
              ResultNeedKey(
                gk,
                mbAcoid =>
                  resume(mbAcoid).flatMap(x =>
                    Result
                      .sequence(results_)
                      .map(otherResults => (okResults :+ x) :++ otherResults)
                  ),
              )
            case ResultNeedLocalKeyVisible(stakeholders, resume) =>
              ResultNeedLocalKeyVisible(
                stakeholders,
                visible =>
                  resume(visible).flatMap(x =>
                    Result.sequence(results_).map(otherResults => (okResults :+ x) :++ otherResults)
                  ),
              )
          }
      }
    go(BackStack.empty, results0).map(_.toImmArray)
  }

  def assert(assertion: Boolean)(err: Error): Result[Unit] =
    if (assertion)
      ResultDone.Unit
    else
      ResultError(err)

  implicit val resultInstance: Monad[Result] = new Monad[Result] {
    override def point[A](a: => A): Result[A] = ResultDone(a)
    override def bind[A, B](fa: Result[A])(f: A => Result[B]): Result[B] = fa.flatMap(f)
  }
}
