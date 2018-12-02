/*
 * (c) Copyright Christian P. Fries, Germany. All rights reserved. Contact: email@christianfries.com.
 *
 * Created on 15.06.2016
 */

package net.finmath.montecarlo.automaticdifferentiation;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.Assert;
import org.junit.Test;

import net.finmath.exception.CalculationException;
import net.finmath.functions.AnalyticFormulas;
import net.finmath.montecarlo.AbstractRandomVariableFactory;
import net.finmath.montecarlo.BrownianMotion;
import net.finmath.montecarlo.BrownianMotionInterface;
import net.finmath.montecarlo.RandomVariable;
import net.finmath.montecarlo.RandomVariableFactory;
import net.finmath.montecarlo.assetderivativevaluation.AssetModelMonteCarloSimulationInterface;
import net.finmath.montecarlo.assetderivativevaluation.BlackScholesModel;
import net.finmath.montecarlo.assetderivativevaluation.MonteCarloAssetModel;
import net.finmath.montecarlo.assetderivativevaluation.products.DigitalOption;
import net.finmath.montecarlo.assetderivativevaluation.products.DigitalOptionDeltaLikelihood;
import net.finmath.montecarlo.automaticdifferentiation.backward.RandomVariableDifferentiableAADFactory;
import net.finmath.montecarlo.automaticdifferentiation.backward.RandomVariableDifferentiableAADFactory.DiracDeltaApproximationMethod;
import net.finmath.montecarlo.conditionalexpectation.LinearRegression;
import net.finmath.montecarlo.model.AbstractModel;
import net.finmath.montecarlo.process.AbstractProcess;
import net.finmath.montecarlo.process.ProcessEulerScheme;
import net.finmath.stochastic.RandomVariableInterface;
import net.finmath.stochastic.Scalar;
import net.finmath.time.TimeDiscretization;
import net.finmath.time.TimeDiscretizationInterface;

/**
 * This test checks several methods for calculating the delta of a digital option. Among the methods
 * is the calculation via AAD with regression,
 * see Stochastic Algorithmic Differentiation of (Expectations of) Discontinuous Functions (Indicator Functions).
 * https://ssrn.com/abstract=3282667
 *
 * The test shows that the method provides a significant improvement in the quality (in terms of variance reduction):
 * AAD is 8 time better than finite differences,
 * AAD with regression is 3 times better than AAD
 * Likelihood Ratio (the benchmark for a digital option) is only 6% better than AAD with regression.
 *
 * @author Christian Fries
 */
public class MonteCarloBlackScholesModelDigitalOptionAADRegressionSensitivitiesTest {

	private static DecimalFormat formatterReal4 = new DecimalFormat("0.0000");

	// Model properties
	private final double	modelInitialValue   = 1.0;
	private final double	modelRiskFreeRate   = 0.05;
	private final double	modelVolatility     = 0.50;

	// Process discretization properties
	private final int		numberOfPaths		= 200000;
	private final int		numberOfTimeSteps	= 1;
	private final double	deltaT				= 1.0;

	// Product properties
	private final int		assetIndex = 0;
	private final double	optionMaturity = 1.0;
	private final double	optionStrike = 1.05;

	@Test
	public void test() throws CalculationException {

		int seed = 3141;
		double width = 0.05;

		Map<String, Object> results = getSensitivityApproximations(width, seed, true);

		Double deltaAnalytic = (Double)results.get("delta.analytic");

		RandomVariableInterface deltaFD = (RandomVariableInterface)results.get("delta.fd");
		RandomVariableInterface deltaAAD = (RandomVariableInterface)results.get("delta.aad");
		RandomVariableInterface deltaAADRegression = (RandomVariableInterface)results.get("delta.aad.regression");
		RandomVariableInterface deltaLikelihoodRatio = (RandomVariableInterface)results.get("delta.likelihood");

		RandomVariableInterface deltaAADRegressionDirect = (RandomVariableInterface)results.get("delta.aad.directregression");

		System.out.println("Digital Option Delta\n");
		System.out.println("finite difference..........: " + formatterReal4.format(deltaFD.getAverage()) + "\t" + formatterReal4.format(deltaFD.getAverage()-deltaAnalytic));
		System.out.println("algorithmic diff...........: " + formatterReal4.format(deltaAAD.getAverage()) + "\t" + formatterReal4.format(deltaAAD.getAverage()-deltaAnalytic));
		System.out.println("algo diff with regression.1: " + formatterReal4.format(deltaAADRegressionDirect.getAverage()) + "\t" + formatterReal4.format(deltaAADRegressionDirect.getAverage()-deltaAnalytic));
		System.out.println("algo diff with regression.2: " + formatterReal4.format(deltaAADRegression.getAverage()) + "\t" + formatterReal4.format(deltaAADRegression.getAverage()-deltaAnalytic));
		System.out.println("likelihood ratio...........: " + formatterReal4.format(deltaLikelihoodRatio.getAverage()) + "\t" + formatterReal4.format(deltaLikelihoodRatio.getAverage()-deltaAnalytic));
		System.out.println("analytic...................: " + formatterReal4.format(deltaAnalytic) + "\t" + formatterReal4.format(0));
		System.out.println();

		Assert.assertEquals("digital option delta finite difference", deltaAnalytic, deltaFD.getAverage(), 1E-1);
		Assert.assertEquals("digital option delta aad", deltaAnalytic, deltaAAD.getAverage(), 1E-2);
		Assert.assertEquals("digital option delta aad regression", deltaAnalytic, deltaAADRegressionDirect.getAverage(), 4E-3);
		Assert.assertEquals("digital option delta likelihood ratio", deltaAnalytic, deltaLikelihoodRatio.getAverage(), 4E-3);
	}

	/**
	 * Create a Monte-Carlo simulation of a Black-Scholes model using a specified Brownian motion
	 * and random variable factory. The random variable factory will control the use of AAD (by means of dependency injection).
	 *
	 * @param randomVariableFactory The random variable factory to be used.
	 * @param brownianMotion The Brownian motion used to drive the model.
	 * @return A Monte-Carlo simulation of a Black-Scholes model.
	 */
	public MonteCarloAssetModel getModel(AbstractRandomVariableFactory randomVariableFactory, BrownianMotionInterface brownianMotion) {
		// Create a model
		AbstractModel model = new BlackScholesModel(modelInitialValue, modelRiskFreeRate, modelVolatility, randomVariableFactory);

		// Create a corresponding MC process
		AbstractProcess process = new ProcessEulerScheme(brownianMotion);

		// Using the process (Euler scheme), create an MC simulation of a Black-Scholes model
		return new MonteCarloAssetModel(model, process);
	}

	public Map<String, Object> getSensitivityApproximations(double width, int seed, boolean isDirectRegression) throws CalculationException {
		Map<String, Object> results = new HashMap<String, Object>();

		// Create Brownian motion with specified seed
		TimeDiscretizationInterface timeDiscretization = new TimeDiscretization(0.0 /* initial */, numberOfTimeSteps, deltaT);
		BrownianMotionInterface brownianMotion = new BrownianMotion(timeDiscretization, 1 /* numberOfFactors */, numberOfPaths, seed);

		/*
		 * Create product
		 */
		DigitalOption option = new DigitalOption(optionMaturity, optionStrike);

		/*
		 * Calculate sensitivities using AAD
		 */
		{
			Map<String, Object> randomVariableProps = new HashMap<String, Object>();
			randomVariableProps.put("diracDeltaApproximationWidthPerStdDev", width);	// 0.05 is the default
			RandomVariableDifferentiableAADFactory randomVariableFactory = new RandomVariableDifferentiableAADFactory(new RandomVariableFactory(), randomVariableProps);

			/*
			 * Create Model
			 */
			MonteCarloAssetModel monteCarloBlackScholesModel = getModel(randomVariableFactory, brownianMotion);


			RandomVariableInterface value = option.getValue(0.0, monteCarloBlackScholesModel);
			Map<Long, RandomVariableInterface> derivative = ((RandomVariableDifferentiableInterface)value).getGradient();
			RandomVariableDifferentiableInterface initialValue = (RandomVariableDifferentiableInterface)((BlackScholesModel)((MonteCarloAssetModel)monteCarloBlackScholesModel).getModel()).getInitialValue()[0];
			RandomVariableInterface deltaAAD = derivative.get(initialValue.getID());

			results.put("delta.aad", deltaAAD);
		}


		/*
		 * AAD with regression (using lib code)
		 */
		{
			Map<String, Object> randomVariableRegressionProps = new HashMap<String, Object>();
			randomVariableRegressionProps.put("diracDeltaApproximationWidthPerStdDev", width);	// 0.05 is the default
			randomVariableRegressionProps.put("diracDeltaApproximationMethod", DiracDeltaApproximationMethod.REGRESSION_ON_DISTRIBUITON.name());
			randomVariableRegressionProps.put("diracDeltaApproximationDensityRegressionWidthPerStdDev", 0.75);	// 0.5 is the default
			RandomVariableDifferentiableAADFactory randomVariableFactoryRegression = new RandomVariableDifferentiableAADFactory(new RandomVariableFactory(), randomVariableRegressionProps);

			/*
			 * Create Model
			 */
			MonteCarloAssetModel monteCarloBlackScholesModel = getModel(randomVariableFactoryRegression, brownianMotion);

			RandomVariableInterface value = option.getValue(0.0, monteCarloBlackScholesModel);
			Map<Long, RandomVariableInterface> derivative = ((RandomVariableDifferentiableInterface)value).getGradient();
			RandomVariableDifferentiableInterface initialValue = (RandomVariableDifferentiableInterface)((BlackScholesModel)((MonteCarloAssetModel)monteCarloBlackScholesModel).getModel()).getInitialValue()[0];

			RandomVariableInterface deltaRegression = derivative.get(initialValue.getID());

			results.put("delta.aad.regression", deltaRegression);
		}

		/*
		 * Analytic
		 */
		double deltaAnalytic = AnalyticFormulas.blackScholesDigitalOptionDelta(modelInitialValue, modelRiskFreeRate, modelVolatility, optionMaturity, optionStrike);
		results.put("delta.analytic", deltaAnalytic);


		/*
		 * Other Methods
		 */
		MonteCarloAssetModel monteCarloBlackScholesModel = getModel(new RandomVariableFactory(), brownianMotion);
		RandomVariableInterface X = monteCarloBlackScholesModel.getAssetValue(optionMaturity, assetIndex).sub(optionStrike);

		/*
		 * Finite Difference
		 */
		{

			double epsilon = width*X.getStandardDeviation();
			Map<String, Object> shiftedValues = new HashMap<String, Object>();
			shiftedValues.put("initialValue", modelInitialValue+epsilon/2.0);
			RandomVariableInterface valueUp = option.getValue(0.0, monteCarloBlackScholesModel.getCloneWithModifiedData(shiftedValues));

			shiftedValues.put("initialValue", modelInitialValue-epsilon/2.0);
			RandomVariableInterface valueDn = option.getValue(0.0, monteCarloBlackScholesModel.getCloneWithModifiedData(shiftedValues));

			RandomVariableInterface deltaFD = valueUp.sub(valueDn).div(epsilon);

			results.put("delta.fd", deltaFD);
		}

		/*
		 * Likelihood ratio
		 */
		{
			DigitalOptionDeltaLikelihood digitalOption = new DigitalOptionDeltaLikelihood(optionMaturity, optionStrike);
			RandomVariableInterface deltaLikelihoodRatio = new Scalar(digitalOption.getValue(monteCarloBlackScholesModel));

			results.put("delta.likelihood", deltaLikelihoodRatio);
		}

		/*
		 * Calculate sensitivities using AAD with regression by extracting the derivative A
		 *
		 * The following code is only for research purposes. We can explicitly extract the random variables A and X
		 * in the valuation of the derivative being E(A d/dX 1_X ) and analyse the random variables.
		 */
		if(isDirectRegression) {
			/*
			 * Calculate A from
			 */
			Map<String, Object> randomVariablePropsZeroWidth = new HashMap<String, Object>();
			randomVariablePropsZeroWidth.put("diracDeltaApproximationWidthPerStdDev", 0.0);
			RandomVariableDifferentiableAADFactory randomVariableFactoryZeroWidth = new RandomVariableDifferentiableAADFactory(new RandomVariableFactory(), randomVariablePropsZeroWidth);

			Map<String, Object> randomVariablePropsInftyWidth = new HashMap<String, Object>();
			randomVariablePropsInftyWidth.put("diracDeltaApproximationWidthPerStdDev", Double.POSITIVE_INFINITY);
			RandomVariableDifferentiableAADFactory randomVariableFactoryInftyWidth = new RandomVariableDifferentiableAADFactory(new RandomVariableFactory(), randomVariablePropsInftyWidth);

			AssetModelMonteCarloSimulationInterface monteCarloBlackScholesModelZeroWidth = getModel(randomVariableFactoryZeroWidth, brownianMotion);
			AssetModelMonteCarloSimulationInterface monteCarloBlackScholesModelInftyWidth = getModel(randomVariableFactoryInftyWidth, brownianMotion);

			RandomVariableDifferentiableInterface initialValueZeroWidth = (RandomVariableDifferentiableInterface)((BlackScholesModel)((MonteCarloAssetModel)monteCarloBlackScholesModelZeroWidth).getModel()).getInitialValue()[0];
			RandomVariableDifferentiableInterface initialValueInftyWidth = (RandomVariableDifferentiableInterface)((BlackScholesModel)((MonteCarloAssetModel)monteCarloBlackScholesModelInftyWidth).getModel()).getInitialValue()[0];

			RandomVariableInterface A0 = ((RandomVariableDifferentiableInterface)option.getValue(0.0, monteCarloBlackScholesModelZeroWidth)).getGradient().get(initialValueZeroWidth.getID());
			RandomVariableInterface A1 = ((RandomVariableDifferentiableInterface)option.getValue(0.0, monteCarloBlackScholesModelInftyWidth)).getGradient().get(initialValueInftyWidth.getID());

			RandomVariableInterface A = A1.sub(A0);

			/*
			 * Density regression
			 */
			double underlyingStdDev = X.getStandardDeviation();
			ArrayList<Double> maskX = new ArrayList<Double>();
			ArrayList<Double> maskY = new ArrayList<Double>();
			for(double maskSizeFactor = -0.5; maskSizeFactor<0.505; maskSizeFactor+=0.01) {
				double maskSize2 = maskSizeFactor * underlyingStdDev;
				if(Math.abs(maskSizeFactor) < 1E-10) continue;
				RandomVariableInterface maskPos = X.add(Math.max(maskSize2,0)).choose(new Scalar(1.0), new Scalar(0.0));
				RandomVariableInterface maskNeg = X.add(Math.min(maskSize2,0)).choose(new Scalar(0.0), new Scalar(1.0));
				RandomVariableInterface mask2 = maskPos.mult(maskNeg);
				double density = mask2.getAverage() / Math.abs(maskSize2);
				maskX.add(maskSize2);
				maskY.add(density);
			}
			RandomVariableInterface densityX = new RandomVariable(0.0, ArrayUtils.toPrimitive(maskX.toArray(new Double[0])));
			RandomVariableInterface densityValues = new RandomVariable(0.0, ArrayUtils.toPrimitive(maskY.toArray(new Double[0])));

			double[] densityRegressionCoeff = new LinearRegression(new RandomVariableInterface[] { densityX.mult(0.0).add(1.0), densityX }).getRegressionCoefficients(densityValues);
			double density = densityRegressionCoeff[0];

			RandomVariableInterface densityRegression = densityValues.mult(0.0).add(densityRegressionCoeff[0]);
			for(int i=1; i<densityRegressionCoeff.length; i++) {
				densityRegression = densityRegression.add(densityX.pow(i).mult(densityRegressionCoeff[i]));
			}
			results.put("density", density);
			results.put("density.x", densityX);
			results.put("density.values", densityValues);
			results.put("density.regression", densityRegression);

			/*
			 * Localization
			 */
			double derivativeLocalizationSize = width*X.getStandardDeviation();
			RandomVariableInterface derivativeLocalizer = X.add(derivativeLocalizationSize/2.0).choose(new Scalar(1.0), new Scalar(0.0));
			derivativeLocalizer = derivativeLocalizer.mult(X.sub(derivativeLocalizationSize/2.0).choose(new Scalar(0.0), new Scalar(1.0)));

			RandomVariableInterface Atilde = A.mult(derivativeLocalizer);
			RandomVariableInterface Xtilde = X.mult(derivativeLocalizer);

			/*
			 * Linear regression of A
			 */
			double alphaLinear = (Xtilde.squared().getAverage() * Atilde.getAverage() - Xtilde.getAverage() * Xtilde.mult(Atilde).getAverage()) / (Xtilde.squared().getAverage() - Xtilde.average().squared().doubleValue());

			/*
			 * Non-linear regression of A
			 */

			//		double[] adjointDerivativeRegressionCoeff = new LinearRegression(new RandomVariableInterface[] { derivativeLocalizer, Xtilde }).getRegressionCoefficients(Atilde);
			double[] adjointDerivativeRegressionCoeff = new LinearRegression(new RandomVariableInterface[] { derivativeLocalizer }).getRegressionCoefficients(Atilde);

			double alphaNonLinear = adjointDerivativeRegressionCoeff[0] * density;

			RandomVariableInterface deltaAADReg = A0.add(alphaNonLinear);

			RandomVariableInterface sensitivityRegression = new Scalar(adjointDerivativeRegressionCoeff[0]);
			RandomVariableInterface densityRegressionOnX = new Scalar(densityRegressionCoeff[0]);
			for(int i=1; i<adjointDerivativeRegressionCoeff.length; i++) {
				sensitivityRegression = sensitivityRegression.add(X.pow(i).mult(adjointDerivativeRegressionCoeff[i]));
				densityRegressionOnX = densityRegressionOnX.add(X.pow(i).mult(densityRegressionCoeff[i]));
			}

			results.put("delta.aad.directregression", deltaAADReg);
			results.put("delta.aad.directregression.regression.x", X);
			results.put("delta.aad.directregression.regression.sensitivity", sensitivityRegression);
			results.put("delta.aad.directregression.regression.density", densityRegressionOnX);
		}

		return results;
	}
}
