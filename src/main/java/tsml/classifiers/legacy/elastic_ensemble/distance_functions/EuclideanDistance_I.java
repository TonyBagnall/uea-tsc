/*
 * This file is part of the UEA Time Series Machine Learning (TSML) toolbox.
 *
 * The UEA TSML toolbox is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU General Public License as published 
 * by the Free Software Foundation, either version 3 of the License, or 
 * (at your option) any later version.
 *
 * The UEA TSML toolbox is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with the UEA TSML toolbox. If not, see <https://www.gnu.org/licenses/>.
 */
 
package tsml.classifiers.legacy.elastic_ensemble.distance_functions;

import static utilities.multivariate_tools.MultivariateInstanceTools.splitMultivariateInstance;
import weka.core.EuclideanDistance;
import weka.core.Instance;
import weka.core.Instances;

/**
 *
 * @author Aaron
 */


public class EuclideanDistance_I extends EuclideanDistance{
       
    public EuclideanDistance_I(){}
    
    public EuclideanDistance_I(Instances train){
        super(train);
        
        m_Data = null;
        m_Validated = true;
    }
    
    

    @Override
    public double distance(Instance multiSeries1, Instance multiseries2, double cutoff){
        
        //split the instance.
        Instance[] multi1 = splitMultivariateInstance(multiSeries1);
        Instance[] multi2 = splitMultivariateInstance(multiseries2);

        //TODO: might need to normalise here.
        double[][] data1 = utilities.multivariate_tools.MultivariateInstanceTools.convertMultiInstanceToTransposedArrays(multi1);
        double[][] data2 = utilities.multivariate_tools.MultivariateInstanceTools.convertMultiInstanceToTransposedArrays(multi2);
        return distance(data1, data2, cutoff);
    }
    
    public double distance(double[][] a, double[][] b, double cutoff){
        //assume a and b are the same length.
        double sum =0;
        for(int i=0; i<a.length; i++){
            sum += Math.sqrt(sqMultiDist(a[i],b[i]));
        }
        return sum;
    }
    
    double sqDist(double a, double b){
        return (a-b)*(a-b);
    }
    
    //given each aligned value in the channel.
    double sqMultiDist(double[] a, double[] b){
        double sum = 0;
        for(int i=0; i<a.length; i++){
            sum += sqDist(a[i], b[i]);
        }
        return sum;
    }
}
